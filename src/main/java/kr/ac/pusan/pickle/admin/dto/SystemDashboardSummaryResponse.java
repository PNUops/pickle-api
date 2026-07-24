package kr.ac.pusan.pickle.admin.dto;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.inventory.NodeStatus;

/** Contract schema {@code SystemDashboardSummary} (SYS_ADMIN only). */
public record SystemDashboardSummaryResponse(
        List<NodeRatio> nodes,
        Map<String, Long> vmCountsByStatus,
        Tasks tasks,
        long notificationFailureCount,
        long certExpiring30dCount,
        long openDriftFindingCount,
        long sshPasswordEnabledVmCount,
        List<IpPoolUsage> ipPools) {

    /** Per-node allocation ratios (same aggregates as {@code GET /admin/nodes}). */
    public record NodeRatio(
            Long id,
            String name,
            NodeStatus status,
            double cpuOvercommitRatio,
            double memoryAllocRatio,
            boolean warn) {
    }

    public record Tasks(
            long runningCount,
            long retryingCount,
            long needsAdminCount,
            long failed24hCount) {
    }

    public record IpPoolUsage(
            Long id,
            String name,
            String cidr,
            long allocatedCount,
            long freeCount) {
    }
}
