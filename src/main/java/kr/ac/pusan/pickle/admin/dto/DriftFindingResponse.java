package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.provisioning.DriftFinding;
import kr.ac.pusan.pickle.provisioning.DriftFindingKind;
import kr.ac.pusan.pickle.provisioning.DriftFindingStatus;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Contract {@code DriftFindingView}. */
public record DriftFindingResponse(
        Long id,
        DriftFindingKind kind,
        @Nullable Long vmId,
        @Nullable String vmName,
        @Nullable Integer proxmoxVmid,
        @Nullable String nodeName,
        String summary,
        @Nullable JsonNode detail,
        DriftFindingStatus status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        @Nullable Instant resolvedAt,
        @Nullable Long resolvedById,
        @Nullable String resolvedByEmail,
        @Nullable String resolutionNote) {

    public static DriftFindingResponse from(DriftFinding finding, JsonNode detail,
            String resolvedByEmail, String vmName) {
        return new DriftFindingResponse(finding.getId(), finding.getKind(), finding.getVmId(),
                vmName, finding.getProxmoxVmid(), finding.getNodeName(), finding.getSummary(), detail,
                finding.getStatus(), finding.getFirstSeenAt(), finding.getLastSeenAt(),
                finding.getResolvedAt(), finding.getResolvedBy(), resolvedByEmail,
                finding.getResolutionNote());
    }
}
