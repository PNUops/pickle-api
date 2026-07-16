package kr.ac.pusan.pickle.notification;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.notification.dto.NotificationView;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes in-app notifications and handles read receipts (contract tag
 * {@code notifications}).
 *
 * <p>{@link #publish} INSERTs rows <b>inside the caller's transaction</b> so a
 * rolled-back business tx leaves no orphan notification; callers without an
 * active tx (pipeline jobs commit step-by-step) write immediately. Per-user
 * dedup collisions ({@code dedup_key}) are absorbed by
 * {@code ON CONFLICT DO NOTHING} — a duplicate publish is a per-recipient
 * no-op. Email delivery is asynchronous: rows start {@code PENDING} (or
 * {@code SKIPPED} for email-off events) and {@link NotificationDispatchJob}
 * drains them.</p>
 */
@Service
public class NotificationService {

    private static final String INSERT_SQL = """
            insert into notifications
                (user_id, event, title, body, link_path, importance, payload, dedup_key, status)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::notification_status)
            on conflict (user_id, dedup_key) where dedup_key is not null do nothing
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationComposer composer;
    private final NotificationRepository notificationRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;

    public NotificationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            NotificationComposer composer, NotificationRepository notificationRepository,
            GroupMemberRepository groupMemberRepository, UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.composer = composer;
        this.notificationRepository = notificationRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
    }

    // ── publishing ─────────────────────────────────────────────────────────

    /** Single-recipient convenience for {@link #publish(Collection, NotificationEvent, Map, String)}. */
    public void publish(long recipientUserId, NotificationEvent event, Map<String, Object> args,
            String dedupKey) {
        publish(List.of(recipientUserId), event, args, dedupKey);
    }

    /**
     * Renders the event once and inserts one row per recipient in the caller's
     * transaction. {@code dedupKey} (nullable) makes the publish idempotent
     * per recipient.
     */
    public void publish(Collection<Long> recipientUserIds, NotificationEvent event,
            Map<String, Object> args, String dedupKey) {
        if (recipientUserIds.isEmpty()) {
            return;
        }
        NotificationComposer.Composed composed = composer.compose(event, args);
        String payloadJson = composed.payload() == null ? null
                : objectMapper.writeValueAsString(composed.payload());
        String status = (event.emailEnabled() ? NotificationStatus.PENDING
                : NotificationStatus.SKIPPED).name();
        for (Long userId : recipientUserIds) {
            jdbcTemplate.update(INSERT_SQL, userId, composed.eventId(), composed.title(),
                    composed.body(), composed.linkPath(), composed.importance().name(),
                    payloadJson, dedupKey, status);
        }
    }

    // ── recipient resolution helpers (ACTIVE users only) ───────────────────

    /** ACTIVE group members holding OWNER (optionally EDITOR too). */
    public List<Long> groupRoleHolderIds(long groupId, boolean includeEditors) {
        List<Long> memberIds = groupMemberRepository.findByGroupIdOrderByIdAsc(groupId).stream()
                .filter(m -> m.getRole() == GroupMemberRole.OWNER
                        || (includeEditors && m.getRole() == GroupMemberRole.EDITOR))
                .map(GroupMember::getUserId)
                .toList();
        return userRepository.findAllById(memberIds).stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .toList();
    }

    /** Every ACTIVE member of the group. */
    public List<Long> groupMemberIds(long groupId) {
        List<Long> memberIds = groupMemberRepository.findByGroupIdOrderByIdAsc(groupId).stream()
                .map(GroupMember::getUserId)
                .toList();
        return userRepository.findAllById(memberIds).stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .toList();
    }

    /** The org's ACTIVE ORG_ADMINs. */
    public List<Long> orgAdminIds(long orgId) {
        return userRepository.findByRoleAndOrgId(UserRole.ORG_ADMIN, orgId).stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .toList();
    }

    /** All ACTIVE SYS_ADMINs. */
    public List<Long> sysAdminIds() {
        return userRepository.findByRole(UserRole.SYS_ADMIN).stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .toList();
    }

    // ── read receipts ──────────────────────────────────────────────────────

    /**
     * Marks one own notification read. Idempotent: {@code readAt} is
     * first-write-wins and a re-read answers 200 with the original timestamp.
     * Other users' rows answer 404 (existence masked).
     */
    @Transactional
    public NotificationView markRead(long actorUserId, long notificationId) {
        Notification notification = notificationRepository
                .findByIdAndUserId(notificationId, actorUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.RESOURCE_NOT_FOUND,
                        "리소스를 찾을 수 없습니다", "해당 알림이 존재하지 않습니다."));
        // microsecond precision: what PostgreSQL stores — the immediate
        // response and every later read then render the identical timestamp
        notification.markRead(java.time.Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return NotificationView.from(notification);
    }

    /** Marks every unread own notification read; returns the update count. */
    @Transactional
    public int markAllRead(long actorUserId) {
        return jdbcTemplate.update("""
                update notifications set read_at = now()
                 where user_id = ? and read_at is null
                """, actorUserId);
    }
}
