package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.provisioning.DriftFinding;
import kr.ac.pusan.pickle.provisioning.DriftFindingKind;
import kr.ac.pusan.pickle.provisioning.DriftFindingStatus;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Contract {@code DriftFindingView}. */
public record DriftFindingResponse(
        UUID id,
        DriftFindingKind kind,
        @Nullable UUID vmId,
        @Nullable String vmName,
        @Nullable Integer proxmoxVmid,
        @Nullable String nodeName,
        String summary,
        @Nullable JsonNode detail,
        DriftFindingStatus status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        @Nullable Instant resolvedAt,
        @Nullable UUID resolvedById,
        @Nullable String resolvedByEmail,
        @Nullable String resolutionNote) {

    public static DriftFindingResponse from(DriftFinding finding, JsonNode detail,
            UUID vmId, String vmName, UUID resolvedById, String resolvedByEmail) {
        return new DriftFindingResponse(finding.getPublicId(), finding.getKind(), vmId,
                vmName, finding.getProxmoxVmid(), finding.getNodeName(), finding.getSummary(), detail,
                finding.getStatus(), finding.getFirstSeenAt(), finding.getLastSeenAt(),
                finding.getResolvedAt(), resolvedById, resolvedByEmail,
                finding.getResolutionNote());
    }
}
