package kr.ac.pusan.pickle.audit;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes append-only {@code audit_logs} rows. Runs in its own transaction so
 * audit entries survive business-transaction rollbacks (e.g. failed logins).
 * Detail maps are whitelisted field-by-field at call sites — never secrets
 * (docs/plan/07).
 */
@Service
public class AuditService {

    public static final String AUTH_SIGNUP = "auth.signup";
    public static final String AUTH_VERIFY = "auth.verify";
    public static final String AUTH_LOGIN = "auth.login";
    public static final String AUTH_LOGIN_FAILED = "auth.login_failed";
    public static final String AUTH_REFRESH_REUSE_DETECTED = "auth.refresh_reuse_detected";
    public static final String AUTH_LOGOUT = "auth.logout";
    public static final String GROUP_CREATE = "group.create";
    public static final String GROUP_DELETE = "group.delete";
    public static final String GROUP_MEMBER_ADD = "group.member_add";
    public static final String GROUP_MEMBER_UPDATE = "group.member_update";
    public static final String GROUP_MEMBER_REMOVE = "group.member_remove";
    public static final String REQUEST_CREATE = "request.create";
    public static final String REQUEST_CANCEL = "request.cancel";
    public static final String REQUEST_APPROVE = "request.approve";
    public static final String REQUEST_REJECT = "request.reject";
    public static final String ORG_CREATE = "org.create";
    public static final String ORG_UPDATE = "org.update";
    public static final String USER_ROLE_UPDATE = "user.role_update";
    public static final String VM_SELF_DELETE = "vm.self_delete";
    public static final String VM_SCHEDULE_DELETE = "vm.schedule_delete";
    public static final String VM_CANCEL_SCHEDULED_DELETE = "vm.cancel_scheduled_delete";
    public static final String VM_FORCE_DELETE = "vm.force_delete";
    /** Initial-password reveal (every reveal since v0.7.0) — the fact only, never the value. */
    public static final String VM_PASSWORD_REVEAL = "vm.password_reveal";
    // HTTP publishing (M4A, docs/plan/06).
    public static final String VM_PUBLISH = "vm.publish";
    public static final String VM_PUBLICATION_UPDATE = "vm.publication_update";
    public static final String VM_UNPUBLISH = "vm.unpublish";
    public static final String DOMAIN_DELETE = "domain.delete";
    public static final String DOMAIN_VERIFY = "domain.verify";
    public static final String ROUTE_RESYNC = "route.resync";
    // Operations (M5, contract tag admin).
    public static final String DRIFT_RESOLVE = "drift.resolve";
    public static final String TASK_RETRY = "task.retry";
    public static final String NOTIFICATION_RESEND = "notification.resend";
    public static final String VM_PERIOD_UPDATE = "vm.period_update";
    /**
     * SSH gateway route resolved (docs/api/internal.md Link 1). Retired as an
     * audit action by the gate-C split (2026-07-18): the route lookup runs on an
     * unauthenticated offered key, so an allowed lookup is no longer audited —
     * the authenticated record is {@link #SSHGW_SESSION}. Kept only for reading
     * pre-split rows.
     */
    public static final String SSHGW_ROUTE = "sshgw.route";
    /** SSH gateway route denied (unknown/blocked/not-running/kill-switch). Actor null. */
    public static final String SSHGW_ROUTE_DENIED = "sshgw.route_denied";
    /**
     * Authenticated SSH session established (docs/api/internal.md Link 1
     * {@code /session}, gate-C fix). Emitted from sshpiperd's PipeStart, after
     * signature verification — this is the per-user attribution G6 requires.
     */
    public static final String SSHGW_SESSION = "sshgw.session";
    // M5 admin surfaces (contract v0.5.0).
    public static final String SETTING_UPDATE = "setting.update";
    public static final String ANNOUNCEMENT_CREATE = "announcement.create";
    // M5.5 SSH keys / VM settings / password (contract v0.8.0). Never the key
    // material — only the fact and non-secret metadata (fingerprint/keyId).
    public static final String USER_SSH_KEY_ADD = "user.ssh_key_add";
    public static final String USER_SSH_KEY_GENERATE = "user.ssh_key_generate";
    public static final String USER_SSH_KEY_DOWNLOAD = "user.ssh_key_download";
    public static final String USER_SSH_KEY_DELETE = "user.ssh_key_delete";
    public static final String VM_SETTING_UPDATE = "vm.setting_update";
    public static final String VM_PASSWORD_REGENERATE = "vm.password_regenerate";
    /** Actor role stamped on gateway route audits (no user identity in v1). */
    public static final String ACTOR_ROLE_SSHGW = "SSHGW";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    /** Lazy self-reference so {@link #recordAfterCommit} can invoke the
     *  REQUIRES_NEW {@link #record} through the proxy (self-invocation would
     *  bypass it); ObjectProvider defers resolution and breaks the cycle. */
    private final ObjectProvider<AuditService> self;

    public AuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            ObjectProvider<AuditService> self) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String actorRole, String action, String targetType, Long targetId,
            Map<String, Object> detail, String ip) {
        String detailJson = (detail == null || detail.isEmpty()) ? null : objectMapper.writeValueAsString(detail);
        jdbcTemplate.update("""
                insert into audit_logs (actor_id, actor_role, action, target_type, target_id, detail, ip)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, actorId, actorRole, action, targetType, targetId, detailJson, ip);
    }

    /**
     * Records a <b>success</b> audit only once the surrounding business
     * transaction commits: the REQUIRES_NEW {@link #record} runs in an
     * afterCommit callback, so a business tx that rolls back at commit leaves
     * no false success row (a bug REQUIRES_NEW alone cannot prevent — it would
     * commit the audit even when the outer tx never does). If no transaction is
     * active the write happens immediately.
     *
     * <p>Use this for success-path business audits (approvals, deletions, role
     * and membership changes). Failure/security audits — AUTH_LOGIN_FAILED,
     * refresh-reuse detection — must survive their business tx's rollback and
     * therefore keep calling {@link #record} directly.</p>
     */
    public void recordAfterCommit(Long actorId, String actorRole, String action, String targetType,
            Long targetId, Map<String, Object> detail, String ip) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record(actorId, actorRole, action, targetType, targetId, detail, ip);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.getObject().record(actorId, actorRole, action, targetType, targetId, detail, ip);
            }
        });
    }
}
