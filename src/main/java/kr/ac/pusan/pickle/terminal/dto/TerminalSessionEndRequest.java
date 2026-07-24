package kr.ac.pusan.pickle.terminal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * bridge → pickle-api session end (the internal web-terminal contract,
 * {@code POST /internal/terminal/session-end}). Fired exactly once when the
 * session closes, for any reason. The byte values are <b>counts only</b>
 * (frame content is never sent, logged, or audited).
 *
 * @param sessionId       the session that ended
 * @param reason          {@code CLIENT_CLOSED|IDLE_TIMEOUT|FORCE_TERMINATED|
 *                        REVALIDATION_DENIED|SSH_FAILED|BRIDGE_SHUTDOWN}
 * @param durationSeconds session wall-clock duration
 * @param bytesIn         bytes relayed browser→VM (count only)
 * @param bytesOut        bytes relayed VM→browser (count only)
 */
public record TerminalSessionEndRequest(@NotBlank String sessionId, String reason,
        Long durationSeconds, Long bytesIn, Long bytesOut) {
}
