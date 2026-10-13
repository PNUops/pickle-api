package kr.ac.pusan.pickle.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import org.jspecify.annotations.Nullable;

/** Current access increases and queued future resources remain separate observations. */
public record ActorAccessImpact(String fingerprint, List<AccessIncrease> increases,
        List<PendingResourceOwner> pendingResourceOwners) {
    @Schema(name = "AccessIncrease")
    public record AccessIncrease(@Nullable ResourceType resourceType, UUID targetId, UUID workspaceId,
            @Nullable String beforeRole, String afterRole) { }
    @Schema(name = "PendingResourceOwner")
    public record PendingResourceOwner(UUID requestId, UUID workspaceId, ResourceType resourceType,
            String projectedStatus, String role) { }
    public boolean requiresConfirmation() { return !increases.isEmpty() || !pendingResourceOwners.isEmpty(); }
}
