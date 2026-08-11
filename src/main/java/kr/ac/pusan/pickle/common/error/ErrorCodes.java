package kr.ac.pusan.pickle.common.error;

/** Stable machine-readable error codes (contract: Problem.code). */
public final class ErrorCodes {

    public static final String AUTH_VERIFICATION_TOKEN_EXPIRED = "AUTH_VERIFICATION_TOKEN_EXPIRED";
    public static final String AUTH_INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
    public static final String AUTH_EMAIL_NOT_VERIFIED = "AUTH_EMAIL_NOT_VERIFIED";
    public static final String AUTH_REFRESH_TOKEN_INVALID = "AUTH_REFRESH_TOKEN_INVALID";
    public static final String AUTH_TOKEN_INVALID = "AUTH_TOKEN_INVALID";
    public static final String AUTH_CSRF_INVALID = "AUTH_CSRF_INVALID";
    // Account lifecycle (contract tag me/auth/admin, v0.9.0).
    public static final String AUTH_PASSWORD_MISMATCH = "AUTH_PASSWORD_MISMATCH";
    /** Sudo-mode (v0.24.0): the endpoint demands a valid X-Reauth-Token. */
    public static final String REAUTH_REQUIRED = "REAUTH_REQUIRED";
    public static final String AUTH_RESET_TOKEN_EXPIRED = "AUTH_RESET_TOKEN_EXPIRED";
    // 2FA (TOTP) — contract tag me/auth/admin, v0.9.0.
    public static final String AUTH_MFA_CODE_INVALID = "AUTH_MFA_CODE_INVALID";
    public static final String AUTH_MFA_TOKEN_EXPIRED = "AUTH_MFA_TOKEN_EXPIRED";
    public static final String MFA_ALREADY_ENROLLED = "MFA_ALREADY_ENROLLED";
    public static final String MFA_SETUP_NOT_IN_PROGRESS = "MFA_SETUP_NOT_IN_PROGRESS";
    public static final String MFA_NOT_ENROLLED = "MFA_NOT_ENROLLED";
    /** Admin-tier account is unenrolled while enforcement is on — every op but mfa/auth/me/meta is 403'd. */
    public static final String MFA_ENROLLMENT_REQUIRED = "MFA_ENROLLMENT_REQUIRED";
    // Terms/consent — contract tag me/reference, v0.9.0.
    public static final String CONSENT_VERSION_MISMATCH = "CONSENT_VERSION_MISMATCH";
    public static final String ACCOUNT_SOLE_OWNER_OF_ACTIVE_WORKSPACE = "ACCOUNT_SOLE_OWNER_OF_ACTIVE_WORKSPACE";
    public static final String ACCOUNT_HAS_ACTIVE_VMS = "ACCOUNT_HAS_ACTIVE_VMS";
    public static final String ACCOUNT_SELF_DISABLE_FORBIDDEN = "ACCOUNT_SELF_DISABLE_FORBIDDEN";
    public static final String ACCOUNT_NOT_DISABLED = "ACCOUNT_NOT_DISABLED";
    /** Disable target is not in a disable-able state (already DISABLED, or WITHDRAWN). */
    public static final String ACCOUNT_INVALID_STATE = "ACCOUNT_INVALID_STATE";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String WORKSPACE_MEMBER_MANAGE_FORBIDDEN = "WORKSPACE_MEMBER_MANAGE_FORBIDDEN";
    public static final String WORKSPACE_MEMBER_USER_NOT_FOUND = "WORKSPACE_MEMBER_USER_NOT_FOUND";
    public static final String WORKSPACE_MEMBER_ALREADY_EXISTS = "WORKSPACE_MEMBER_ALREADY_EXISTS";
    public static final String WORKSPACE_SOLE_OWNER_REMOVAL = "WORKSPACE_SOLE_OWNER_REMOVAL";
    public static final String WORKSPACE_ROLE_INSUFFICIENT = "WORKSPACE_ROLE_INSUFFICIENT";
    /**
     * The caller is not a member of the workspace at all, on a path where every
     * member is allowed. Distinct from {@link #WORKSPACE_ROLE_INSUFFICIENT}
     * because the remedy is different: being added to the workspace, not being
     * moved up a rung.
     */
    public static final String WORKSPACE_MEMBERSHIP_REQUIRED = "WORKSPACE_MEMBERSHIP_REQUIRED";
    // Workspace deletion (contract v0.9.0).
    public static final String WORKSPACE_HAS_ACTIVE_VMS = "WORKSPACE_HAS_ACTIVE_VMS";
    public static final String WORKSPACE_PERSONAL_UNDELETABLE = "WORKSPACE_PERSONAL_UNDELETABLE";
    /**
     * The workspace the operation would act in has been soft-deleted. Raised on
     * approval, where the resource would otherwise be created inside a workspace
     * that no longer exists.
     */
    public static final String WORKSPACE_DELETED = "WORKSPACE_DELETED";
    public static final String REQUEST_ALREADY_DECIDED = "REQUEST_ALREADY_DECIDED";
    public static final String REQUEST_REQUESTER_INELIGIBLE = "REQUEST_REQUESTER_INELIGIBLE";
    public static final String VM_INVALID_STATE = "VM_INVALID_STATE";
    public static final String VM_CONFIRM_NAME_MISMATCH = "VM_CONFIRM_NAME_MISMATCH";
    public static final String VM_ACCESS_GRANT_EXISTS = "VM_ACCESS_GRANT_EXISTS";
    public static final String LLM_KEY_ACCESS_GRANT_EXISTS = "LLM_KEY_ACCESS_GRANT_EXISTS";
    public static final String LLM_KEY_REVOKED = "LLM_KEY_REVOKED";
    // VM protection settings (contract v0.9.0).
    public static final String VM_DELETION_PROTECTED = "VM_DELETION_PROTECTED";
    public static final String VM_STOP_PROTECTED = "VM_STOP_PROTECTED";
    public static final String VM_PASSWORD_ALREADY_VIEWED = "VM_PASSWORD_ALREADY_VIEWED";
    // HTTP publishing (contract tag publishing).
    public static final String DOMAIN_LIMIT_REACHED = "DOMAIN_LIMIT_REACHED";
    public static final String DOMAIN_FQDN_TAKEN = "DOMAIN_FQDN_TAKEN";
    public static final String DOMAIN_NOT_CUSTOM = "DOMAIN_NOT_CUSTOM";
    public static final String DOMAIN_NOT_ACTIVE = "DOMAIN_NOT_ACTIVE";
    // Operations (contract tag admin).
    public static final String VM_EXPIRED = "VM_EXPIRED";
    public static final String TASK_NOT_RETRYABLE = "TASK_NOT_RETRYABLE";
    public static final String DRIFT_FINDING_ALREADY_RESOLVED = "DRIFT_FINDING_ALREADY_RESOLVED";
    public static final String NOTIFICATION_NOT_RESENDABLE = "NOTIFICATION_NOT_RESENDABLE";
    // SSH keys (contract tag me, v0.8.0).
    public static final String SSH_KEY_DUPLICATE = "SSH_KEY_DUPLICATE";
    public static final String SSH_KEY_LIMIT_EXCEEDED = "SSH_KEY_LIMIT_EXCEEDED";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    // Web terminal (contract v0.10.0). Ticket mint refusals surfaced to the
    // console; the internal redeem/revalidate reason codes are separate (below).
    public static final String TERMINAL_DISABLED = "TERMINAL_DISABLED";
    public static final String TERMINAL_SESSION_LIMIT = "TERMINAL_SESSION_LIMIT";
    // Relay port forwarding (contract v0.27.0).
    /** Kill switch {@code port_forwarding_enabled} is off — creation refused. */
    public static final String PORT_FORWARDING_DISABLED = "PORT_FORWARDING_DISABLED";
    /** No free public port left in the relay's band. */
    public static final String PUBLIC_PORT_EXHAUSTED = "PUBLIC_PORT_EXHAUSTED";
    // 교내 IP requests (contract v0.27.0).
    /** The VM already has a live (REQUESTED/APPROVED/GRANTED) campus-IP request. */
    public static final String CAMPUS_IP_REQUEST_EXISTS = "CAMPUS_IP_REQUEST_EXISTS";
    /** The requested status change is not a legal transition from the current state. */
    public static final String CAMPUS_IP_INVALID_TRANSITION = "CAMPUS_IP_INVALID_TRANSITION";
    // 사용량 시계열 (contract v0.35.0). The hypervisor is the source and it can
    // be unreachable; the read answers 503 rather than an empty chart.
    public static final String METRICS_UNAVAILABLE = "METRICS_UNAVAILABLE";
    // 점검 모드 (contract v0.9.0, GET /meta/status): 비관리자 요청 503.
    public static final String MAINTENANCE_MODE = "MAINTENANCE_MODE";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // Internal SSH gateway route resolution (internal route contract). These
    // are the machine-readable denial reasons the sshgw plugin reads; they are
    // NOT part of the public console contract.
    public static final String SSHGW_ROUTE_NOT_FOUND = "SSHGW_ROUTE_NOT_FOUND";
    public static final String SSHGW_GATEWAY_DISABLED = "SSHGW_GATEWAY_DISABLED";
    public static final String SSHGW_VM_NOT_RUNNING = "SSHGW_VM_NOT_RUNNING";
    public static final String SSHGW_VM_BLOCKED = "SSHGW_VM_BLOCKED";
    public static final String SSHGW_ROUTE_NO_ADDRESS = "SSHGW_ROUTE_NO_ADDRESS";
    // Route v2 (per-user identity, internal route contract v2).
    public static final String SSHGW_KEY_UNKNOWN = "SSHGW_KEY_UNKNOWN";
    public static final String SSHGW_KEY_NOT_MEMBER = "SSHGW_KEY_NOT_MEMBER";
    public static final String SSHGW_PASSWORD_DISABLED = "SSHGW_PASSWORD_DISABLED";
    public static final String SSHGW_NO_HOST_KEY = "SSHGW_NO_HOST_KEY";

    private ErrorCodes() {
    }
}
