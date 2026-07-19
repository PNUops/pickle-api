package kr.ac.pusan.pickle.terminal.dto;

import java.time.Instant;

/**
 * Web-terminal one-time ticket (contract {@code TerminalSessionTicketResponse},
 * v0.10.0). The console embeds {@code ticket} as the second WS subprotocol
 * element ({@code ticket.<value>}) and connects to {@code wsPath} on the same
 * origin; the bridge redeems it. {@code wsPath}/{@code subprotocol} are fixed
 * constants echoed for the client's convenience.
 */
public record TerminalTicketResponse(String sessionId, String ticket, String wsPath,
        String subprotocol, Instant expiresAt) {

    public static final String WS_PATH = "/terminal/ws";
    public static final String SUBPROTOCOL = "pickle.terminal.v1";

    public static TerminalTicketResponse of(String sessionId, String ticket, Instant expiresAt) {
        return new TerminalTicketResponse(sessionId, ticket, WS_PATH, SUBPROTOCOL, expiresAt);
    }
}
