package kr.ac.pusan.pickle.terminal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * bridge → pickle-api ticket redeem (the internal web-terminal contract,
 * {@code POST /internal/terminal/redeem}). Carries only the opaque one-time
 * ticket; lookup is by ticket hash, never by user/VM enumeration.
 */
public record TerminalRedeemRequest(@NotBlank String ticket) {
}
