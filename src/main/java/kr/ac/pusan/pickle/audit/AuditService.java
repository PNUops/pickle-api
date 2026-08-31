package kr.ac.pusan.pickle.audit;

import java.util.Map;
import java.util.UUID;
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
    public static final String WORKSPACE_CREATE = "workspace.create";
    public static final String WORKSPACE_DELETE = "workspace.delete";
    public static final String WORKSPACE_MEMBER_ADD = "workspace.member_add";
    public static final String WORKSPACE_MEMBER_UPDATE = "workspace.member_update";
    public static final String WORKSPACE_MEMBER_REMOVE = "workspace.member_remove";
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
    public static final String ACCOUNT_PROFILE_UPDATE = "account.profile_update";
    /** An external login was attached to an account (including an automatic link at first sign-in). */
    public static final String ACCOUNT_IDENTITY_LINKED = "account.identity_linked";
    public static final String ACCOUNT_IDENTITY_UNLINKED = "account.identity_unlinked";
    // 2FA (contract v0.9.0). Secrets/codes are never recorded, only the event.
    public static final String ACCOUNT_MFA_ENROLL = "account.mfa_enroll";
    public static final String ACCOUNT_MFA_DISABLE = "account.mfa_disable";
    public static final String ACCOUNT_MFA_RESET = "account.mfa_reset";
    /**
     * An administrator corrected another account's 직책·학번·소속.
     *
     * <p>Separate from {@code account.profile_update}, which is the holder
     * writing their own: there the actor and the target are the same row and
     * the payload assumes it. Folding an administrator's write into it would
     * make "who changed this 학번" unanswerable, which is the one question the
     * write-once lock exists to keep answerable.
     */
    public static final String USER_PROFILE_UPDATE = "user.profile_update";
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
    /** A workspace owner giving themselves a way inside — recorded apart from the ordinary grant. */
    public static final String VM_ACCESS_BREAK_GLASS = "vm.access_break_glass";

    public static final String LLM_KEY_ACCESS_GRANT_ADD = "llm_key.access_grant_add";
    public static final String LLM_KEY_ACCESS_GRANT_UPDATE = "llm_key.access_grant_update";
    public static final String LLM_KEY_ACCESS_GRANT_REMOVE = "llm_key.access_grant_remove";
    public static final String LLM_KEY_ACCESS_BREAK_GLASS = "llm_key.access_break_glass";
    public static final String LLM_KEY_ISSUE = "llm_key.issue";
    public static final String LLM_KEY_REVOKE = "llm_key.revoke";
    public static final String LLM_KEY_UPDATE = "llm_key.update";
    public static final String LLM_KEY_LIMITS_UPDATE = "llm_key.limits_update";
    public static final String LLM_KEY_SUSPEND = "llm_key.suspend";
    public static final String LLM_KEY_RESUME = "llm_key.resume";
    public static final String OPENROUTER_ACCOUNT_CREATE = "openrouter_account.create";
    public static final String OPENROUTER_ACCOUNT_UPDATE = "openrouter_account.update";
    public static final String OPENROUTER_CREDENTIAL_STAGE = "openrouter_credential.stage";
    public static final String OPENROUTER_CREDENTIAL_ACTIVATE = "openrouter_credential.activate";
    public static final String OPENROUTER_CREDENTIAL_CANCEL = "openrouter_credential.cancel";
    public static final String OPENROUTER_CREDENTIAL_ROLLBACK = "openrouter_credential.rollback";
    public static final String OPENROUTER_CREDENTIAL_FINALIZE = "openrouter_credential.finalize";
    public static final String OPENROUTER_CREDENTIAL_DELETE = "openrouter_credential.delete";
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
    // LLM gateway link (internal contract). The 5-second sync poll is never
    // audited (it would flood the log); the one auditable event the poll can
    // cause is the restored-backup counter raise below.
    /**
     * The gateway reported a generation above ours: this side's counter went
     * backwards (restored backup) while the gateway's persisted high-water
     * mark did not, so the counter was raised above the reported value and
     * the full document served. Actor null.
     */
    public static final String LLM_GATEWAY_GENERATION_RAISE = "llm_gateway.generation_raise";
    // 교내 IP requests (contract v0.27.0).
    public static final String CAMPUS_IP_REQUEST = "campus_ip.request";
    public static final String CAMPUS_IP_CANCEL = "campus_ip.cancel";
    public static final String CAMPUS_IP_STATUS_UPDATE = "campus_ip.status_update";
    // Admin surfaces (contract v0.5.0).
    public static final String SETTING_UPDATE = "setting.update";
    public static final String ANNOUNCEMENT_CREATE = "announcement.create";
    // 공지사항. The target is always the notice, images included — an image has
    // no standing of its own, and naming it as a target would scatter one
    // notice's history across rows nobody can join back together. The detail
    // map carries whitelisted display fields (title, the popup and pinned
    // flags, which fields an edit changed) and never the body bytes of an
    // image.
    public static final String NOTICE_CREATE = "notice.create";
    public static final String NOTICE_UPDATE = "notice.update";
    public static final String NOTICE_DELETE = "notice.delete";
    public static final String NOTICE_IMAGE_ADD = "notice.image_add";
    public static final String NOTICE_IMAGE_DELETE = "notice.image_delete";
    // Web terminal (contract v0.10.0, internal route contract). Actor is
    // the mint-time user (session-start/end) or the admin (force_terminate). The
    // detail map NEVER carries terminal frame/keystroke content — only lifecycle
    // metadata (byte/duration counts, reasons, clientIp); this is asserted by test.
    public static final String TERMINAL_SESSION_START = "terminal.session_start";
    public static final String TERMINAL_SESSION_END = "terminal.session_end";
    public static final String TERMINAL_FORCE_TERMINATE = "terminal.force_terminate";
    // SSH keys / VM settings / password. Never the key material — only the fact
    // and non-secret metadata (fingerprint/keyId). Scoped to the VM since
    // v0.42.0, so the target is the VM rather than a standalone key row.
    public static final String VM_SSH_KEY_ISSUE = "vm.ssh_key_issue";
    public static final String VM_SSH_KEY_REISSUE = "vm.ssh_key_reissue";
    public static final String VM_SSH_KEY_DOWNLOAD = "vm.ssh_key_download";
    public static final String VM_SSH_KEY_DELETE = "vm.ssh_key_delete";
    public static final String VM_SETTING_UPDATE = "vm.setting_update";
    public static final String VM_PASSWORD_REGENERATE = "vm.password_regenerate";
    /** Actor role stamped on gateway route audits (no user identity in v1). */
    public static final String ACTOR_ROLE_SSHGW = "SSHGW";
    /** Actor role stamped on relay-originated audits (sync violations, auto-suspend). */
    public static final String ACTOR_ROLE_RELAY = "RELAY";
    /** Actor role stamped on LLM-gateway-originated audits (no user identity). */
    public static final String ACTOR_ROLE_LLM_GATEWAY = "LLM_GATEWAY";

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

    /**
     * {@code targetId} is the target row's <b>public</b> identifier: the column
     * is text and holds a UUID string. Targets that have no public identity (a
     * refresh token, a settings key) pass null; a settings key names itself in
     * {@code detail}, while a refresh token puts nothing there at all — its row
     * number identified it to nobody, and the actor and action already say whose
     * session did what. Rows written before V78 hold the internal number of the
     * day as text and mean what they always meant.
     *
     * <p>{@code detail} reaches ordinary users through {@code /me/activity}, so
     * an id in it is a public id or it is not written: {@link AuditIds} resolves
     * one where the call site holds only an internal key.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String actorRole, String action, String targetType, UUID targetId,
            Map<String, Object> detail, String ip) {
        String detailJson = (detail == null || detail.isEmpty()) ? null : objectMapper.writeValueAsString(detail);
        jdbcTemplate.update("""
                insert into audit_logs (actor_id, actor_role, action, target_type, target_id, detail, ip)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, actorId, actorRole, action, targetType,
                targetId == null ? null : targetId.toString(), detailJson, ip);
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
            UUID targetId, Map<String, Object> detail, String ip) {
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
