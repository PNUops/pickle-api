package kr.ac.pusan.pickle.audit.dto;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Contract {@code AuditLogView}: one audit row with actor context. Actor
 * fields are null for system/automated rows. {@code actorRole} is the role
 * <b>at action time</b> (the stored {@code actor_role} column), kept as text —
 * internal actors (e.g. the SSH gateway) stamp roles outside the user enum.
 */
public record AuditLogViewResponse(
        long id,
        Long actorId,
        String actorEmail,
        String actorName,
        String actorRole,
        String action,
        String targetType,
        String targetId,
        JsonNode detail,
        String ip,
        String orgName,
        Instant createdAt) {
}
