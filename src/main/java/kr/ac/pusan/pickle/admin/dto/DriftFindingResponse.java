package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.provisioning.DriftFinding;
import kr.ac.pusan.pickle.provisioning.DriftFindingKind;
import kr.ac.pusan.pickle.provisioning.DriftFindingStatus;
import tools.jackson.databind.JsonNode;

/** Contract {@code DriftFindingView}. */
public record DriftFindingResponse(
        Long id,
        DriftFindingKind kind,
        Long vmId,
        Integer proxmoxVmid,
        String nodeName,
        String summary,
        JsonNode detail,
        DriftFindingStatus status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant resolvedAt,
        Long resolvedById,
        String resolvedByEmail,
        String resolutionNote) {

    public static DriftFindingResponse from(DriftFinding finding, JsonNode detail,
            String resolvedByEmail) {
        return new DriftFindingResponse(finding.getId(), finding.getKind(), finding.getVmId(),
                finding.getProxmoxVmid(), finding.getNodeName(), finding.getSummary(), detail,
                finding.getStatus(), finding.getFirstSeenAt(), finding.getLastSeenAt(),
                finding.getResolvedAt(), finding.getResolvedBy(), resolvedByEmail,
                finding.getResolutionNote());
    }
}
