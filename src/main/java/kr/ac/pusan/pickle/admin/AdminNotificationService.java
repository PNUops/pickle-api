package kr.ac.pusan.pickle.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminNotificationResponse;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.notification.NotificationChannel;
import kr.ac.pusan.pickle.notification.NotificationImportance;
import kr.ac.pusan.pickle.notification.NotificationStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SYS_ADMIN delivery log (contract {@code listAdminNotifications} /
 * {@code resendAdminNotification}). Reads are plain SQL over
 * {@code notifications ⋈ users} (the notification package owns the write
 * paths; this service only observes them). Resend is a CAS FAILED→PENDING
 * with {@code next_attempt_at = now()} — attempts intentionally keep counting,
 * so each resend gives the dispatcher exactly one more shot before parking
 * FAILED again.
 */
@Service
public class AdminNotificationService {

    private static final String BASE_WHERE = """
             where (?::notification_status is null or n.status = ?::notification_status)
               and (?::text is null or n.event = ?)
               and (?::text is null or u.email = ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public AdminNotificationService(JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    public PageResponse<AdminNotificationResponse> list(NotificationStatus status, String event,
            String email, int page, int size) {
        String statusName = status == null ? null : status.name();
        Object[] filters = {statusName, statusName, event, event, email, email};
        long total = jdbcTemplate.queryForObject(
                "select count(*) from notifications n join users u on u.id = n.user_id"
                        + BASE_WHERE, Long.class, filters);
        List<AdminNotificationResponse> content = jdbcTemplate.query("""
                select n.public_id, n.event, n.title, n.body, n.link_path, n.importance,
                       n.created_at,
                       n.read_at, u.public_id as user_public_id, u.email,
                       n.channel::text as channel,
                       n.status::text as status, n.attempts, n.last_error, n.sent_at
                  from notifications n
                  join users u on u.id = n.user_id
                """ + BASE_WHERE + """
                 order by n.created_at desc, n.id desc
                 limit ? offset ?
                """, AdminNotificationService::mapRow,
                statusName, statusName, event, event, email, email, size, (long) page * size);
        return new PageResponse<>(content, page, size, total,
                (int) ((total + size - 1) / size));
    }

    /** CAS FAILED→PENDING due now; anything else answers 409 (404 when absent). */
    @Transactional
    public MessageResponse resend(AuthenticatedUser actor, UUID notificationId, String ip) {
        int updated = jdbcTemplate.update("""
                update notifications
                   set status = 'PENDING', next_attempt_at = now()
                 where public_id = ? and status = 'FAILED'
                """, notificationId);
        if (updated == 0) {
            Long found = jdbcTemplate.queryForObject(
                    "select count(*) from notifications where public_id = ?", Long.class,
                    notificationId);
            if (found == null || found == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "리소스를 찾을 수 없습니다", "해당 알림이 존재하지 않습니다.");
            }
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.NOTIFICATION_NOT_RESENDABLE,
                    "재발송할 수 없는 알림입니다", "발송에 실패한(FAILED) 알림만 재발송할 수 있습니다.");
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.NOTIFICATION_RESEND, "notification", notificationId, Map.of(), ip);
        return new MessageResponse("알림 재발송을 접수했습니다. 잠시 후 발송 상태가 갱신됩니다.");
    }

    private static AdminNotificationResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AdminNotificationResponse(
                rs.getObject("public_id", java.util.UUID.class),
                rs.getString("event"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("link_path"),
                NotificationImportance.valueOf(rs.getString("importance")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("read_at")),
                rs.getObject("user_public_id", java.util.UUID.class),
                rs.getString("email"),
                NotificationChannel.valueOf(rs.getString("channel")),
                NotificationStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                instant(rs.getTimestamp("sent_at")));
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
