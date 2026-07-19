package kr.ac.pusan.pickle.terminal.dto;

import java.time.Instant;

/**
 * Live web-terminal session, admin view (contract {@code TerminalSessionView},
 * v0.10.0). Assembled from the in-memory mirror plus VM/user/group/org joins;
 * carries no terminal content. Ordered by {@code startedAt} descending in the
 * list response.
 */
public record TerminalSessionView(String sessionId, long vmId, String vmName, long orgId,
        String orgName, String groupName, long userId, String userEmail, String userName,
        String clientIp, Instant startedAt) {
}
