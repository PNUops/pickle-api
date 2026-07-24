package kr.ac.pusan.pickle.audit.dto;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Contract {@code ActivityEntry}: one self-view audit row (login history included). */
public record ActivityEntryResponse(
        long id,
        String action,
        @Nullable String targetType,
        @Nullable String targetId,
        @Nullable JsonNode detail,
        @Nullable String ip,
        Instant createdAt) {
}
