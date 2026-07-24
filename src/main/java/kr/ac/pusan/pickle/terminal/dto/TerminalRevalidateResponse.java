package kr.ac.pusan.pickle.terminal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Revalidation result (the internal web-terminal contract). {@code allow=true} keeps
 * the session; {@code allow=false} carries a {@code reason} (same table as
 * redeem, plus {@code SESSION_UNKNOWN} → the bridge closes WS 1001 "서버 점검").
 * {@code reason} is omitted when allowed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TerminalRevalidateResponse(boolean allow, String reason) {

    public static TerminalRevalidateResponse allowed() {
        return new TerminalRevalidateResponse(true, null);
    }

    public static TerminalRevalidateResponse denied(String reason) {
        return new TerminalRevalidateResponse(false, reason);
    }
}
