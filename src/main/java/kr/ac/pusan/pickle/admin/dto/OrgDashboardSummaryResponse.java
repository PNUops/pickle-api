package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code OrgDashboardSummary} (ORG_ADMIN home / SYS_ADMIN org drill-in). */
public record OrgDashboardSummaryResponse(
        long pendingRequestCount,
        RecentDecisions recentDecisions14d,
        Map<String, Long> vmCountsByStatus,
        Resource resource,
        List<TopWorkspace> topWorkspacesByVmCount,
        long publishedServiceCount,
        long expiringVmCount30d,
        Attention attention) {

    public record RecentDecisions(long approvedCount, long rejectedCount) {
    }

    /** Allocation vs platform capacity + Korean guidance (same math as approval context). */
    public record Resource(
            long allocatedVcpu,
            long allocatedMemoryMb,
            long allocatedDiskGb,
            @Nullable Long capacityVcpu,
            @Nullable Long capacityMemoryMb,
            @Schema(description = "ACTIVE 노드의 thin pool 용량 합(GB) — 용량 미등록 노드가 있으면 null")
            @Nullable Long capacityDiskGb,
            String guidance) {
    }

    public record TopWorkspace(Long workspaceId, String name, long vmCount) {
    }

    public record Attention(long failedTaskCount, long needsAdminVmCount, long expiredVmCount) {
    }
}
