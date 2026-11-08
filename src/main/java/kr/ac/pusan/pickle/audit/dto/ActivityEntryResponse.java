package kr.ac.pusan.pickle.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Contract {@code ActivityEntry}: one self-view audit row (login history included). */
public record ActivityEntryResponse(
        @Schema(description = "활동 행의 공개 ID이며, 조회 파라미터로 쓰지 않아요.")
        UUID id,
        String action,
        @Nullable String targetType,
        @Nullable String targetId,
        @Nullable JsonNode detail,
        @Nullable String ip,
        Instant createdAt) {
}
