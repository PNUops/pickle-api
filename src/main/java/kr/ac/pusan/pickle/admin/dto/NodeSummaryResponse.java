package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.inventory.NodeStatus;

/**
 * Contract schema {@code NodeSummary}: node identity/capacity plus the
 * allocation aggregates and warning thresholds computed by
 * {@code AdminNodeQueryService}.
 */
public record NodeSummaryResponse(
        Long id,
        String name,
        NodeStatus status,
        int cpuThreads,
        int memoryMb,
        String vmBridge,
        String storage,
        long runningVms,
        long allocatedVcpu,
        long allocatedMemoryMb,
        double cpuOvercommitRatio,
        double memoryAllocRatio,
        double cpuWarnThreshold,
        double memoryWarnThreshold,
        IpPoolSummaryResponse ipPool) {
}
