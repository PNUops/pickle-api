package kr.ac.pusan.pickle.admin.dto;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code OrgDashboardSummary} (ORG_ADMIN home / SYS_ADMIN org drill-in). */
public record OrgDashboardSummaryResponse(
        long pendingRequestCount,
        RecentDecisions recentDecisions14d,
        Map<String, Long> vmCountsByStatus,
        Resource resource,
        List<TopGroup> topGroupsByVmCount,
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
            String guidance) {
    }

    public record TopGroup(Long groupId, String name, long vmCount) {
    }

    public record Attention(long failedTaskCount, long needsAdminVmCount, long expiredVmCount) {
    }
}
