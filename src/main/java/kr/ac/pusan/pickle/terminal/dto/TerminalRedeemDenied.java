package kr.ac.pusan.pickle.terminal.dto;

/**
 * Ticket redeem denied (the internal web-terminal contract, HTTP 403). Carries only the
 * machine-readable {@code reason} the bridge maps to a WS close code
 * ({@code TICKET_INVALID}→4000, {@code VM_NOT_RUNNING}→4003,
 * {@code ACCESS_REVOKED}→4004, {@code TERMINAL_DISABLED}→4005). No user-facing
 * prose — this is an infra-to-infra contract.
 */
public record TerminalRedeemDenied(String reason) {
}
