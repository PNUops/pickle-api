package kr.ac.pusan.pickle.audit.dto;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Contract {@code ActivityEntry}: one self-view audit row (login history included). */
public record ActivityEntryResponse(
        long id,
        String action,
        String targetType,
        String targetId,
        JsonNode detail,
        String ip,
        Instant createdAt) {
}
