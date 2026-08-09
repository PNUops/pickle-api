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
 * Detail maps are whitelisted field-by-field at call sites — never secrets.
 */
@Service
public class AuditService {

    public static final String AUTH_SIGNUP = "auth.signup";
    /** Sudo-mode issue attempt (v0.24.0) — meta.result = success/mismatch. */
    public static final String AUTH_REVERIFY = "auth.reverify";
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
    // Account lifecycle (contract v0.9.0). Never the password material —
    // only the fact of the change.
    public static final String ACCOUNT_PASSWORD_CHANGE = "account.password_change";
    public static final String ACCOUNT_PASSWORD_RESET = "account.password_reset";
    public static final String ACCOUNT_WITHDRAW = "account.withdraw";
    // 2FA (contract v0.9.0). Secrets/codes are never recorded, only the event.
    public static final String ACCOUNT_MFA_ENROLL = "account.mfa_enroll";
    public static final String ACCOUNT_MFA_DISABLE = "account.mfa_disable";
    public static final String ACCOUNT_MFA_RESET = "account.mfa_reset";
    public static final String USER_DISABLE = "user.disable";
    public static final String USER_ENABLE = "user.enable";
    public static final String VM_SELF_DELETE = "vm.self_delete";
    public static final String VM_SCHEDULE_DELETE = "vm.schedule_delete";
    public static final String VM_CANCEL_SCHEDULED_DELETE = "vm.cancel_scheduled_delete";
    public static final String VM_FORCE_DELETE = "vm.force_delete";
    /** Initial-password reveal (every reveal since v0.7.0) — the fact only, never the value. */
    public static final String VM_PASSWORD_REVEAL = "vm.password_reveal";
    public static final String VM_ACCESS_GRANT_ADD = "vm.access_grant_add";
    public static final String VM_ACCESS_GRANT_UPDATE = "vm.access_grant_update";
    public static final String VM_ACCESS_GRANT_REMOVE = "vm.access_grant_remove";
    /** A group owner giving themselves a way inside — recorded apart from the ordinary grant. */
    public static final String VM_ACCESS_BREAK_GLASS = "vm.access_break_glass";
    // HTTP publishing.
    public static final String VM_PUBLISH = "vm.publish";
    public static final String DOMAIN_UPDATE = "domain.update";
    public static final String DOMAIN_DELETE = "domain.delete";
    public static final String DOMAIN_VERIFY = "domain.verify";
    public static final String ROUTE_RESYNC = "route.resync";
    // Admin post-hoc domain intervention (contract v0.18.0).
    public static final String DOMAIN_FORCE_RELEASE = "domain.force_release";
    public static final String DOMAIN_ADMIN_VERIFY = "domain.admin_verify";
    public static final String ROUTE_APPLY = "route.apply";
    // Operational-state write paths for inventory (contract v0.21.0).
    public static final String OS_IMAGE_STATUS_UPDATE = "os_image.status_update";
    public static final String FLAVOR_CREATE = "flavor.create";
    public static final String FLAVOR_UPDATE = "flavor.update";
    public static final String NODE_STATUS_UPDATE = "node.status_update";
    // Operations (contract tag admin).
    public static final String DRIFT_RESOLVE = "drift.resolve";
    public static final String TASK_RETRY = "task.retry";
    public static final String NOTIFICATION_RESEND = "notification.resend";
    public static final String VM_PERIOD_UPDATE = "vm.period_update";
    /** Per-VM SSH-gateway/web-terminal block toggled on (contract v0.16.0). */
    public static final String VM_GATEWAY_BLOCK = "vm.gateway_block";
    /** Per-VM SSH-gateway/web-terminal block toggled off. */
    public static final String VM_GATEWAY_UNBLOCK = "vm.gateway_unblock";
    // Admin power intervention (contract v0.17.0) — org-scoped, stop-protection bypass.
    public static final String VM_ADMIN_START = "vm.admin_start";
    public static final String VM_ADMIN_SHUTDOWN = "vm.admin_shutdown";
    public static final String VM_ADMIN_REBOOT = "vm.admin_reboot";
    public static final String VM_ADMIN_FORCE_STOP = "vm.admin_force_stop";
    /**
     * SSH gateway route resolved (internal route contract). Retired as an
     * audit action when route lookup and session auth were split (2026-07-18):
     * the route lookup runs on an
     * unauthenticated offered key, so an allowed lookup is no longer audited —
     * the authenticated record is {@link #SSHGW_SESSION}. Kept only for reading
     * pre-split rows.
     */
    public static final String SSHGW_ROUTE = "sshgw.route";
    /** SSH gateway route denied (unknown/blocked/not-running/kill-switch). Actor null. */
    public static final String SSHGW_ROUTE_DENIED = "sshgw.route_denied";
    /**
     * Authenticated SSH session established (internal route contract
     * {@code /session}). Emitted from sshpiperd's PipeStart, after
     * signature verification — this is the per-user attribution the internal
     * route contract requires.
     */
    public static final String SSHGW_SESSION = "sshgw.session";
    // Relay port forwarding (contract v0.27.0). The sync path itself is not
    // audited (a 30 s heartbeat would flood the log) — only violations and
    // state changes are.
    /** Agent reported an impossible appliedGeneration (out of range or regressing). */
    public static final String RELAY_SYNC_VIOLATION = "relay.sync_violation";
    /** Sync-token (re)issue — the fact only, never the token. */
    public static final String RELAY_TOKEN_ISSUE = "relay.token_issue";
    public static final String VM_PORT_FORWARD_CREATE = "vm.port_forward_create";
    public static final String VM_PORT_FORWARD_DELETE = "vm.port_forward_delete";
    /** Admin suspend, or the threshold auto-suspend (detail.auto=true, actor null). */
    public static final String PORT_MAPPING_SUSPEND = "port_mapping.suspend";
    public static final String PORT_MAPPING_UNSUSPEND = "port_mapping.unsuspend";
    public static final String PORT_MAPPING_DELETE = "port_mapping.delete";
    public static final String PORT_MAPPING_GUARDS_UPDATE = "port_mapping.guards_update";
    // 교내 IP requests (contract v0.27.0).
    public static final String CAMPUS_IP_REQUEST = "campus_ip.request";
    public static final String CAMPUS_IP_CANCEL = "campus_ip.cancel";
    public static final String CAMPUS_IP_STATUS_UPDATE = "campus_ip.status_update";
    // Admin surfaces (contract v0.5.0).
    public static final String SETTING_UPDATE = "setting.update";
    public static final String ANNOUNCEMENT_CREATE = "announcement.create";
    // Web terminal (contract v0.10.0, internal route contract). Actor is
    // the mint-time user (session-start/end) or the admin (force_terminate). The
    // detail map NEVER carries terminal frame/keystroke content — only lifecycle
    // metadata (byte/duration counts, reasons, clientIp); this is asserted by test.
    public static final String TERMINAL_SESSION_START = "terminal.session_start";
    public static final String TERMINAL_SESSION_END = "terminal.session_end";
    public static final String TERMINAL_FORCE_TERMINATE = "terminal.force_terminate";
    // SSH keys / VM settings / password (contract v0.8.0). Never the key
    // material — only the fact and non-secret metadata (fingerprint/keyId).
    public static final String USER_SSH_KEY_ADD = "user.ssh_key_add";
    public static final String USER_SSH_KEY_GENERATE = "user.ssh_key_generate";
    public static final String USER_SSH_KEY_DOWNLOAD = "user.ssh_key_download";
    public static final String USER_SSH_KEY_DELETE = "user.ssh_key_delete";
    public static final String VM_SETTING_UPDATE = "vm.setting_update";
    public static final String VM_PASSWORD_REGENERATE = "vm.password_regenerate";
    /** Actor role stamped on gateway route audits (no user identity in v1). */
    public static final String ACTOR_ROLE_SSHGW = "SSHGW";
    /** Actor role stamped on relay-originated audits (sync violations, auto-suspend). */
    public static final String ACTOR_ROLE_RELAY = "RELAY";

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
