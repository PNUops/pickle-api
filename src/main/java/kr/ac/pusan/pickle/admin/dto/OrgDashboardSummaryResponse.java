package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code OrgDashboardSummary} (ORG_ADMIN home / SYS_ADMIN org drill-in). */
public record OrgDashboardSummaryResponse(
        @Nullable Long pendingRequestCount,
        @Nullable RecentDecisions recentDecisions14d,
        @Nullable Map<String, Long> vmCountsByStatus,
        @Nullable Resource resource,
        @Nullable List<TopWorkspace> topWorkspacesByVmCount,
        @Nullable Long publishedServiceCount,
        @Nullable Long expiringVmCount30d,
        @Nullable Attention attention) {

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

    public record TopWorkspace(UUID workspaceId, String name, long vmCount) {
    }

    public record Attention(long failedTaskCount, long needsAdminVmCount, long expiredVmCount) {
    }
}
