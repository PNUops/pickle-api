package kr.ac.pusan.pickle.notification;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.access.AccessGranteeType;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.notification.dto.NotificationView;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
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
 * no-op. Email delivery is asynchronous: rows start {@code PENDING} and
 * {@link NotificationDispatchJob} drains them ({@code SKIPPED} is written by
 * the dispatcher for recipients deactivated after enqueue).</p>
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
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceAccessGrantRepository grantRepository;
    private final UserRepository userRepository;

    public NotificationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            NotificationComposer composer, NotificationRepository notificationRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            ResourceAccessGrantRepository grantRepository, UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.composer = composer;
        this.notificationRepository = notificationRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.grantRepository = grantRepository;
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
        String status = NotificationStatus.PENDING.name();
        for (Long userId : recipientUserIds) {
            jdbcTemplate.update(INSERT_SQL, userId, composed.eventId(), composed.title(),
                    composed.body(), composed.linkPath(), composed.importance().name(),
                    payloadJson, dedupKey, status);
        }
    }

    // ── recipient resolution helpers (ACTIVE users only) ───────────────────

    /** ACTIVE owners of the workspace. */
    public List<Long> workspaceOwnerIds(long workspaceId) {
        List<Long> memberIds = workspaceMemberRepository.findByWorkspaceIdOrderByIdAsc(workspaceId).stream()
                .filter(m -> m.getRole() == WorkspaceMemberRole.OWNER)
                .map(WorkspaceMember::getUserId)
                .toList();
        return activeAmong(memberIds);
    }

    /**
     * Who hears about one VM's operational life — expiry, provisioning
     * outcomes, a domain or route that failed: the people its access list makes
     * responsible for it, plus the owners of the workspace that owns it.
     *
     * <p>This used to be the workspace's owners and editors. That rung no longer
     * says anything about a particular VM, so the question moved to the VM's
     * own list; right after the changeover both answers name the same people.
     */
    public List<Long> vmResponsibleIds(Vm vm) {
        return resourceRecipients(ResourceType.VM, vm.getId(), vm.getWorkspaceId(),
                List.of(ResourceRole.OWNER, ResourceRole.EDITOR));
    }

    /** Narrower audience: only those the list makes an owner of the VM. */
    public List<Long> vmOwnerIds(Vm vm) {
        return resourceRecipients(ResourceType.VM, vm.getId(), vm.getWorkspaceId(),
                List.of(ResourceRole.OWNER));
    }

    /**
     * Everyone the VM concerns — every grantee at any rung plus the workspace's
     * owners. For news that matters to whoever was using it, above all its
     * deletion.
     */
    public List<Long> vmAudienceIds(Vm vm) {
        return resourceRecipients(ResourceType.VM, vm.getId(), vm.getWorkspaceId(),
                List.of(ResourceRole.values()));
    }

    /**
     * Who hears about one resource, whatever kind it is: the people its access
     * list names at the given rungs, plus the owners of the workspace that owns
     * it. The rule is the same for every type — a grant is a grant — so a second
     * kind of resource answers this question by calling here with its own type
     * rather than by growing a parallel audience rule beside this one.
     */
    public List<Long> resourceRecipients(ResourceType type, long resourceId, long workspaceId,
            Collection<ResourceRole> roles) {
        Set<Long> ids = grantRepository
                .findByResourceTypeAndResourceIdAndGranteeTypeAndRoleIn(type,
                        resourceId, AccessGranteeType.USER, roles)
                .stream()
                .map(ResourceAccessGrant::getUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        ids.addAll(workspaceMemberRepository.findByWorkspaceIdOrderByIdAsc(workspaceId).stream()
                .filter(m -> m.getRole() == WorkspaceMemberRole.OWNER)
                .map(WorkspaceMember::getUserId)
                .toList());
        return activeAmong(List.copyOf(ids));
    }

    private List<Long> activeAmong(List<Long> userIds) {
        return userRepository.findAllById(userIds).stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .toList();
    }

    /** Every ACTIVE member of the workspace. */
    public List<Long> workspaceMemberIds(long workspaceId) {
        List<Long> memberIds = workspaceMemberRepository.findByWorkspaceIdOrderByIdAsc(workspaceId).stream()
                .map(WorkspaceMember::getUserId)
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
