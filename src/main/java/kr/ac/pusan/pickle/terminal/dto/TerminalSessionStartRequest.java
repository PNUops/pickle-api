package kr.ac.pusan.pickle.terminal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * bridge → pickle-api session start (docs/api/internal.md Link 3a
 * {@code POST /internal/terminal/session-start}). Fired once the SSH channel is
 * live. {@code clientIp} is the CF-range-validated {@code X-Real-IP} the LXC 100
 * TLS tier passed to the bridge (B3).
 */
public record TerminalSessionStartRequest(@NotBlank String sessionId, @NotBlank String clientIp) {
}
