package kr.ac.pusan.pickle.terminal.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Live web-terminal session, admin view (contract {@code TerminalSessionView},
 * v0.10.0). Assembled from the in-memory mirror plus VM/user/workspace/org joins;
 * carries no terminal content. Ordered by {@code startedAt} descending in the
 * list response.
 */
public record TerminalSessionView(String sessionId, UUID vmId, String vmName, UUID orgId,
        String orgName, String workspaceName, UUID userId, String userEmail, String userName,
        String clientIp, Instant startedAt) {
}
