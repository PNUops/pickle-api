package kr.ac.pusan.pickle.audit.dto;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Contract {@code AuditLogView}: one audit row with actor context. Actor
 * fields are null for system/automated rows. {@code actorRole} is the role
 * <b>at action time</b> (the stored {@code actor_role} column), kept as text —
 * internal actors (e.g. the SSH gateway) stamp roles outside the user enum.
 */
public record AuditLogViewResponse(
        long id,
        @Nullable Long actorId,
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
