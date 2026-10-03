package kr.ac.pusan.pickle.mail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.AdminNotificationService;
import kr.ac.pusan.pickle.admin.dto.AdminOrgOperationsResponse.AdminOrgRoleMemberView;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.mail.dto.MailDeliveryAttemptView;
import kr.ac.pusan.pickle.mail.dto.MailDeliveryDetailResponse;
import kr.ac.pusan.pickle.mail.dto.MailDeliveryView;
import kr.ac.pusan.pickle.mail.dto.RequestMailSelectionResponse;
import kr.ac.pusan.pickle.mail.dto.RequestMailSelectionView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Reads immutable delivery evidence; the current account address is labelled separately. */
@Service
public class MailDeliveryQueryService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final MailDeliveryJournal journal;
    private final AsyncMailDispatcher accounts;
    private final AdminNotificationService notifications;
    private final ObjectMapper mapper;
    private final Duration bundleWindow;

    public MailDeliveryQueryService(JdbcTemplate jdbc, MailDeliveryJournal journal,
            AsyncMailDispatcher accounts, AdminNotificationService notifications, ObjectMapper mapper,
            @Value("${pickle.notification.admin-bundle-window:PT60M}") Duration bundleWindow) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.journal = journal;
        this.accounts = accounts;
        this.notifications = notifications;
        this.mapper = mapper;
        this.bundleWindow = bundleWindow;
    }

    private void preserveAvailableHistory() {
        // Each batch commits independently. Never manufacture an old address or attempt history.
        while (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from notifications n where not exists
                    (select 1 from mail_deliveries d where d.notification_public_id = n.public_id))
                """, Boolean.class))) {
            journal.importLegacyBatch(1000);
        }
    }

    private String base() {
        return """
                from mail_deliveries d left join users u on u.public_id = d.recipient_user_id
                left join notifications n on n.id = d.notification_id
                """;
    }

    private String queueExpression() {
        return """
                case when d.state <> 'PENDING' then d.state
                     when d.source_kind = 'ACCOUNT' then 'ACCOUNT_WAIT'
                     when d.attempts > 0 then 'RETRY_WAIT'
                     when d.bundle and exists (select 1 from mail_deliveries prior
                       where prior.recipient_user_id = d.recipient_user_id and prior.bundle
                         and (prior.recipient_email is null or prior.recipient_email = coalesce(d.recipient_email, u.email))
                         and prior.sent_at > now() - :window::interval) then 'BUNDLE_WAIT'
                     else 'NORMAL_WAIT' end
                """;
    }

    public PageResponse<MailDeliveryView> list(String sourceKind, String status, String queueState,
            String event, String email, UUID requestId, UUID announcementId, UUID orgId,
            int page, int size) {
        preserveAvailableHistory();
        var params = new HashMap<String, Object>();
        params.put("window", bundleWindow.toSeconds() + " seconds");
        StringBuilder where = new StringBuilder(" where true");
        filter(where, params, "sourceKind", "d.source_kind", sourceKind);
        filter(where, params, "status", "d.state", status);
        filter(where, params, "queueState", "(" + queueExpression() + ")", queueState);
        filter(where, params, "event", "d.event", event);
        filter(where, params, "email", "d.recipient_email", email);
        filter(where, params, "requestId", "d.request_id", requestId);
        filter(where, params, "announcementId", "d.announcement_id", announcementId);
        filter(where, params, "orgId", "d.org_id", orgId);
        long total = named.queryForObject("select count(*) " + base() + where, params, Long.class);
        params.put("size", size);
        params.put("offset", (long) page * size);
        var rows = named.query(select() + base() + where
                + " order by d.created_at desc, d.id desc limit :size offset :offset", params, this::map);
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    private static void filter(StringBuilder where, Map<String, Object> params, String name,
            String column, Object value) {
        if (value == null || "".equals(value)) return;
        where.append(" and ").append(column).append(" = :").append(name);
        params.put(name, value);
    }

    private String select() {
        return "select d.*, u.email as current_email, n.id is not null as content_available, "
                + queueExpression() + " as queue_state, " + """
                case when d.state = 'PENDING' and d.bundle and d.attempts = 0
                  then greatest(d.next_attempt_at, (select max(prior.sent_at) + :window::interval
                    from mail_deliveries prior where prior.recipient_user_id = d.recipient_user_id
                      and prior.bundle and (prior.recipient_email is null
                        or prior.recipient_email = coalesce(d.recipient_email, u.email)))) else d.next_attempt_at end as eligible_next
                """;
    }

    public MailDeliveryDetailResponse detail(UUID id) {
        preserveAvailableHistory();
        var rows = named.query(select() + base() + " where d.public_id = :id",
                Map.of("id", id, "window", bundleWindow.toSeconds() + " seconds"), this::map);
        if (rows.isEmpty()) throw notFound();
        MailDeliveryView view = rows.getFirst();
        var attempts = jdbc.query("""
                select a.* from mail_delivery_attempts a join mail_deliveries d on d.id = a.delivery_id
                 where d.public_id = ? order by a.attempt_no
                """, (rs, row) -> new MailDeliveryAttemptView(rs.getInt("attempt_no"),
                        rs.getObject("dispatch_id", UUID.class), instant(rs, "started_at"),
                        instant(rs, "completed_at"), rs.getString("outcome"), rs.getString("failure_code")), id);
        var selection = view.requestId() == null ? null : selection(view.requestId()).selection();
        return new MailDeliveryDetailResponse(view, attempts, selection);
    }

    public RequestMailSelectionResponse selection(UUID requestId) {
        if (jdbc.queryForObject("select count(*) from requests where public_id = ?", Integer.class, requestId) == 0) {
            throw notFound();
        }
        var rows = jdbc.queryForList("""
                select s.*, o.public_id as org_public_id from request_notification_selections s
                  join requests r on r.id = s.request_id join orgs o on o.id = s.org_id
                 where r.public_id = ?
                """, requestId);
        if (rows.isEmpty()) return new RequestMailSelectionResponse(requestId, null);
        var row = rows.getFirst();
        var json = mapper.readTree(row.get("recipients").toString());
        List<AdminOrgRoleMemberView> staff = new ArrayList<>();
        json.get("staff").forEach(member -> staff.add(mapper.treeToValue(member, AdminOrgRoleMemberView.class)));
        var requester = mapper.treeToValue(json.get("requester"),
                RequestMailSelectionView.RequestMailRequesterView.class);
        return new RequestMailSelectionResponse(requestId, new RequestMailSelectionView(requestId,
                (UUID) row.get("org_public_id"), ((Number) row.get("policy_revision")).longValue(),
                row.get("mail_mode").toString(), ((Timestamp) row.get("created_at")).toInstant(), staff, requester));
    }

    public MessageResponse resend(AuthenticatedUser actor, UUID id, String ip) {
        var view = detail(id).delivery();
        if (!view.canResend() || view.notificationId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOTIFICATION_NOT_RESENDABLE,
                    "재발송할 수 없습니다", "본문이 남아 있는 확실한 실패 알림만 단건 재발송할 수 있습니다.");
        }
        return notifications.resend(actor, view.notificationId(), ip);
    }

    private MailDeliveryView map(ResultSet rs, int index) throws SQLException {
        UUID id = rs.getObject("public_id", UUID.class);
        String source = rs.getString("source_kind");
        String state = rs.getString("state");
        String queue = rs.getString("queue_state");
        String code = rs.getString("failure_code");
        // Observation is separate from the stored queue state used by list filters.
        boolean unconfirmed = "ACCOUNT".equals(source) && ("PENDING".equals(state) || "SENDING".equals(state))
                && !accounts.ownsAccount(id);
        boolean content = rs.getBoolean("content_available");
        boolean knownAddress = rs.getString("recipient_email") != null;
        boolean definiteFailure = java.util.Set.of("SMTP_REJECTED", "MAIL_PREPARATION_FAILED",
                "MAIL_AUTHENTICATION_FAILED", "MAIL_CONNECTION_FAILED", "MAIL_DELIVERY_DISABLED").contains(code == null ? "" : code);
        boolean resend = "NOTIFICATION".equals(source) && "FAILED".equals(state) && content && knownAddress && definiteFailure;
        String unavailable = resend ? null : "ACCOUNT".equals(source) ? "ACCOUNT_SELF_RETRY"
                : !"FAILED".equals(state) ? "NOT_FAILED" : !content ? "CONTENT_EXPIRED"
                : !knownAddress ? "LEGACY_ADDRESS_UNKNOWN" : "LEGACY_FAILURE_UNCONFIRMED";
        Long revision = rs.getObject("policy_revision") == null ? null : rs.getLong("policy_revision");
        return new MailDeliveryView(id, source, rs.getString("event"), rs.getString("title"),
                rs.getObject("recipient_user_id", UUID.class), rs.getString("recipient_email"),
                rs.getString("current_email"), state, queue, rs.getInt("attempts"), code,
                instant(rs, "eligible_next"), instant(rs, "sent_at"), instant(rs, "created_at"),
                rs.getObject("request_id", UUID.class), rs.getObject("org_id", UUID.class),
                rs.getObject("announcement_id", UUID.class), rs.getObject("notification_public_id", UUID.class),
                rs.getString("link_path"), revision, rs.getString("mail_mode"),
                rs.getString("recipient_email") == null, resend, unavailable, unconfirmed);
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "발송 기록을 찾을 수 없습니다", "해당 발송 또는 신청 기록이 없습니다.");
    }
}
