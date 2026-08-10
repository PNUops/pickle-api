package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
        List<IpPoolUsage> ipPools,
        List<NodeLiveResponse> nodesLive,
        LiveCoverage liveCoverage) {

    /**
     * How many nodes each live measurement in {@code nodesLive} actually
     * covers. A platform total summed over {@code nodesLive} is only the
     * platform total when every node answered: a node whose storage read was
     * refused, or that did not answer at all, leaves a sum smaller than the
     * truth, which reads as free capacity that is not there. A client that
     * sums must compare these counts with {@code nodeCount} and say so when
     * they differ, instead of presenting a subset as the whole.
     */
    public record LiveCoverage(
            @Schema(description = "nodesLive 행 수 (플랫폼 전체 노드 수)")
            int nodeCount,
            @Schema(description = "메모리 측정값이 있는 노드 수 — nodeCount보다 작으면 메모리 합계는 부분 측정")
            int memoryMeasuredNodeCount,
            @Schema(description = "스토리지 측정값이 있는 노드 수 — nodeCount보다 작으면 스토리지 합계는 부분 측정")
            int storageMeasuredNodeCount) {
    }

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
