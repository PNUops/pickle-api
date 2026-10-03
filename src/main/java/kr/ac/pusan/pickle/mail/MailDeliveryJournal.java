package kr.ac.pusan.pickle.mail;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Permanent delivery metadata; account bodies and authentication material stay in memory. */
@Service
public class MailDeliveryJournal {
    private static final Set<String> FINAL_STATES = Set.of("PENDING", "SENT", "FAILED", "UNKNOWN");
    private static final Map<String, String> ACCOUNT_TITLES = Map.of(
            "account.signup_verification", "회원가입 이메일 인증",
            "account.password_reset", "비밀번호 재설정",
            "account.already_registered", "가입된 주소 안내",
            "account.mail", "계정 메일");

    private static final String IMPORT_NOTIFICATION = """
            insert into mail_deliveries (source_kind, notification_id, notification_public_id,
                recipient_user_id, recipient_email, event, title, link_path, request_id, org_id,
                announcement_id, policy_revision, mail_mode, state, attempts, failure_code,
                next_attempt_at, sent_at, bundle, claim_id, claimed_at, created_at)
            select 'NOTIFICATION', n.id, n.public_id, u.public_id,
                n.recipient_email, n.event, n.title, n.link_path,
                coalesce(sr.public_id, pr.public_id), coalesce(so.public_id, po.public_id, ao.public_id),
                a.public_id, s.policy_revision, s.mail_mode, n.status::text, n.attempts,
                %s, n.next_attempt_at, n.sent_at, n.bundle,
                n.delivery_claim_id, n.delivery_claimed_at, n.created_at
              from notifications n join users u on u.id = n.user_id
              left join request_notification_selections s on s.id = n.request_selection_id
              left join requests sr on sr.id = s.request_id
              left join orgs so on so.id = s.org_id
              left join requests pr on pr.public_id::text = n.payload ->> 'requestId'
              left join orgs po on po.id = pr.org_id
              left join announcements a on a.id = n.announcement_id
              left join orgs ao on ao.id = a.org_id
             where n.id in (:ids)
            on conflict (notification_public_id) do nothing
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public MailDeliveryJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Transactional
    public void importNotifications(List<Long> ids) {
        importRows(ids, false);
    }

    /** Only available legacy rows are imported; missing historical addresses remain unknown. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int importLegacyBatch(int limit) {
        if (limit <= 0) return 0;
        List<Long> ids = jdbc.queryForList("""
                select n.id from notifications n where not exists
                    (select 1 from mail_deliveries d where d.notification_public_id = n.public_id)
                 order by n.id limit ?
                """, Long.class, limit);
        return importRows(ids, true);
    }

    private int importRows(List<Long> ids, boolean legacy) {
        if (ids.isEmpty()) return 0;
        return named.update(IMPORT_NOTIFICATION.formatted(
                legacy ? "'LEGACY_STATUS_IMPORT'" : "n.skip_reason"), Map.of("ids", ids));
    }

    /** The caller has already claimed notification rows in this same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void beginNotificationAttempt(List<Long> ids, UUID owner) {
        if (ids.isEmpty()) return;
        importRows(ids, true);
        var params = Map.of("ids", ids, "owner", owner);
        named.update("""
                update mail_deliveries d set state = 'SENDING', attempts = n.attempts,
                    recipient_email = coalesce(n.recipient_email, u.email),
                    claim_id = :owner, claimed_at = n.delivery_claimed_at,
                    next_attempt_at = null, failure_code = null, bundle = n.bundle
                  from notifications n join users u on u.id = n.user_id
                 where d.notification_id = n.id and n.id in (:ids)
                   and n.status::text = 'SENDING' and n.delivery_claim_id = :owner
                """, params);
        named.update("""
                insert into mail_delivery_attempts (delivery_id, attempt_no, dispatch_id, started_at, outcome)
                select d.id, d.attempts, :owner, coalesce(d.claimed_at, now()), 'STARTED'
                  from mail_deliveries d where d.notification_id in (:ids)
                    and d.state = 'SENDING' and d.claim_id = :owner
                on conflict (delivery_id, attempt_no) do nothing
                """, params);
    }

    /** Delivery evidence and the notification's outcome commit or roll back together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finishNotificationAttempt(List<Long> ids, UUID owner, String state,
            @Nullable String code, @Nullable Instant nextAttemptAt) {
        if (ids.isEmpty()) return;
        requireState(state);
        var params = new java.util.HashMap<String, Object>();
        params.put("ids", ids);
        params.put("owner", owner);
        params.put("state", state);
        params.put("outcome", "PENDING".equals(state) ? "FAILED" : state);
        params.put("code", code);
        params.put("next", timestamp(nextAttemptAt));
        named.update("""
                with completed as (
                    update mail_deliveries set state = :state, failure_code = :code,
                        next_attempt_at = :next,
                        sent_at = case when :state = 'SENT' then now() else sent_at end
                     where notification_id in (:ids) and claim_id = :owner
                       and state in ('SENDING', 'UNKNOWN') returning id, attempts
                )
                update mail_delivery_attempts a set outcome = :outcome, failure_code = :code,
                    completed_at = now()
                  from completed c where a.delivery_id = c.id and a.attempt_no = c.attempts
                    and a.dispatch_id = :owner and a.outcome in ('STARTED', 'UNKNOWN')
                """, params);
    }

    /** Notification state belongs to its caller; this method synchronizes only the journal. */
    @Transactional
    public void recordNotificationSkip(long id, String code) {
        importRows(List.of(id), true);
        jdbc.update("""
                update mail_deliveries set state = 'SKIPPED', failure_code = ?, next_attempt_at = null
                 where notification_id = ? and state not in ('SENDING', 'SENT', 'UNKNOWN')
                """, code, id);
    }

    @Transactional
    public UUID enqueueAccount(String email, String event, @Nullable UUID userPublicId,
            @Nullable Instant expiresAt) {
        String safeEvent = ACCOUNT_TITLES.containsKey(event) ? event : "account.mail";
        return jdbc.queryForObject("""
                insert into mail_deliveries (source_kind, recipient_user_id, recipient_email,
                    event, title, state, expires_at)
                values ('ACCOUNT', ?, ?, ?, ?, 'PENDING', ?) returning public_id
                """, UUID.class, userPublicId, email, safeEvent, ACCOUNT_TITLES.get(safeEvent), timestamp(expiresAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginAccountAttempt(UUID deliveryId, UUID owner) {
        List<Long> ids = jdbc.queryForList("""
                update mail_deliveries set state = 'SENDING', attempts = attempts + 1,
                    claim_id = ?, claimed_at = now(), failure_code = null
                 where public_id = ? and source_kind = 'ACCOUNT' and state = 'PENDING'
                returning id
                """, Long.class, owner, deliveryId);
        if (ids.isEmpty()) return false;
        jdbc.update("""
                insert into mail_delivery_attempts (delivery_id, attempt_no, dispatch_id, started_at, outcome)
                select id, attempts, ?, claimed_at, 'STARTED' from mail_deliveries where id = ?
                """, owner, ids.getFirst());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishAccountAttempt(UUID deliveryId, UUID owner, String state, @Nullable String code) {
        if (!Set.of("SENT", "FAILED", "UNKNOWN").contains(state)) {
            throw new IllegalArgumentException("Unsupported account delivery state");
        }
        jdbc.update("""
                with completed as (
                    update mail_deliveries set state = ?, failure_code = ?,
                        sent_at = case when ? = 'SENT' then now() else sent_at end
                     where public_id = ? and source_kind = 'ACCOUNT' and claim_id = ?
                       and state in ('SENDING', 'UNKNOWN') returning id, attempts
                )
                update mail_delivery_attempts a set outcome = ?, failure_code = ?, completed_at = now()
                  from completed c where a.delivery_id = c.id and a.attempt_no = c.attempts
                    and a.dispatch_id = ? and a.outcome in ('STARTED', 'UNKNOWN')
                """, state, code, state, deliveryId, owner, state, code, owner);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void skipAccount(UUID deliveryId, String code) {
        jdbc.update("""
                update mail_deliveries set state = 'SKIPPED', failure_code = ?, next_attempt_at = null
                 where public_id = ? and source_kind = 'ACCOUNT' and state = 'PENDING' and attempts = 0
                """, code, deliveryId);
    }

    private static void requireState(String state) {
        if (!FINAL_STATES.contains(state)) throw new IllegalArgumentException("Unsupported notification outcome");
    }

    private static @Nullable Timestamp timestamp(@Nullable Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
