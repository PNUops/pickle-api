package kr.ac.pusan.pickle.terminal;

/**
 * Machine-readable deny reasons for the internal redeem/revalidate calls
 * (the internal web-terminal contract). These are infra-to-infra reason strings the
 * bridge maps to WS close codes — distinct from the public {@code Problem.code}
 * values in {@link kr.ac.pusan.pickle.common.error.ErrorCodes}.
 */
public final class TerminalReasons {

    /** Unknown / expired / already-used ticket → WS 4000. */
    public static final String TICKET_INVALID = "TICKET_INVALID";
    /** VM no longer RUNNING (or no live IP) → WS 4003. */
    public static final String VM_NOT_RUNNING = "VM_NOT_RUNNING";
    /** User not ACTIVE / membership lost / per-VM admin block → WS 4004. */
    public static final String ACCESS_REVOKED = "ACCESS_REVOKED";
    /** Global kill switch off / maintenance → WS 4005. */
    public static final String TERMINAL_DISABLED = "TERMINAL_DISABLED";
    /** revalidate only: mirror lost the session (e.g. api restart) → WS 1001. */
    public static final String SESSION_UNKNOWN = "SESSION_UNKNOWN";

    private TerminalReasons() {
    }
}
