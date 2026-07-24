package kr.ac.pusan.pickle.terminal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * bridge → pickle-api session start (the internal web-terminal contract,
 * {@code POST /internal/terminal/session-start}). Fired once the SSH channel is
 * live. {@code clientIp} is the CF-range-validated {@code X-Real-IP} the LXC 100
 * TLS tier passed to the bridge.
 */
public record TerminalSessionStartRequest(@NotBlank String sessionId, @NotBlank String clientIp) {
}
