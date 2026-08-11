package kr.ac.pusan.pickle.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Contract {@code AuditLogView}: one audit row with actor context. Actor
 * fields are null for system/automated rows. {@code actorRole} is the role
 * <b>at action time</b> (the stored {@code actor_role} column), kept as text —
 * internal actors (e.g. the SSH gateway) stamp roles outside the user enum.
 */
public record AuditLogViewResponse(
        @Schema(description = "감사 로그 행의 공개 식별자. 목록 렌더링용 키이며 어떤 조회 파라미터도 아닙니다.")
        UUID id,
        @Nullable UUID actorId,
        @Nullable String actorEmail,
        @Nullable String actorName,
        @Nullable String actorRole,
        String action,
        @Nullable String targetType,
        @Nullable String targetId,
        @Nullable JsonNode detail,
        @Nullable String ip,
        @Nullable String orgName,
        Instant createdAt) {
}
