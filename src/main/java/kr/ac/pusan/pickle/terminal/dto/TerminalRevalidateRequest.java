package kr.ac.pusan.pickle.terminal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * bridge → pickle-api revalidation poll (the internal web-terminal contract,
 * {@code POST /internal/terminal/revalidate}), fired every 60s per live session.
 * Idle-timeout is enforced locally by the bridge and is not api state.
 */
public record TerminalRevalidateRequest(@NotBlank String sessionId) {
}
