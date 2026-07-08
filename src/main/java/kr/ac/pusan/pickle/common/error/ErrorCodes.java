package kr.ac.pusan.pickle.common.error;

/** Stable machine-readable error codes (contract: Problem.code). */
public final class ErrorCodes {

    public static final String AUTH_EMAIL_ALREADY_REGISTERED = "AUTH_EMAIL_ALREADY_REGISTERED";
    public static final String AUTH_VERIFICATION_TOKEN_EXPIRED = "AUTH_VERIFICATION_TOKEN_EXPIRED";
    public static final String AUTH_INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
    public static final String AUTH_EMAIL_NOT_VERIFIED = "AUTH_EMAIL_NOT_VERIFIED";
    public static final String AUTH_REFRESH_TOKEN_INVALID = "AUTH_REFRESH_TOKEN_INVALID";
    public static final String AUTH_TOKEN_INVALID = "AUTH_TOKEN_INVALID";
    public static final String AUTH_CSRF_INVALID = "AUTH_CSRF_INVALID";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String GROUP_SLUG_DUPLICATE = "GROUP_SLUG_DUPLICATE";
    public static final String GROUP_MEMBER_MANAGE_FORBIDDEN = "GROUP_MEMBER_MANAGE_FORBIDDEN";
    public static final String GROUP_MEMBER_USER_NOT_FOUND = "GROUP_MEMBER_USER_NOT_FOUND";
    public static final String GROUP_MEMBER_ALREADY_EXISTS = "GROUP_MEMBER_ALREADY_EXISTS";
    public static final String GROUP_SOLE_OWNER_REMOVAL = "GROUP_SOLE_OWNER_REMOVAL";
    public static final String GROUP_ROLE_INSUFFICIENT = "GROUP_ROLE_INSUFFICIENT";
    public static final String ORG_SLUG_DUPLICATE = "ORG_SLUG_DUPLICATE";
    public static final String REQUEST_ALREADY_DECIDED = "REQUEST_ALREADY_DECIDED";
    public static final String VM_INVALID_STATE = "VM_INVALID_STATE";
    public static final String VM_CONFIRM_NAME_MISMATCH = "VM_CONFIRM_NAME_MISMATCH";
    public static final String VM_PASSWORD_ALREADY_VIEWED = "VM_PASSWORD_ALREADY_VIEWED";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
