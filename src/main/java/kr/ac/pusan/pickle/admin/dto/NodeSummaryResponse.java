package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code NodeSummary}: node identity/capacity plus the
 * allocation aggregates and warning thresholds computed by
 * {@code AdminNodeQueryService}.
 */
public record NodeSummaryResponse(
        UUID id,
        String name,
        NodeStatus status,
        int cpuThreads,
        int memoryMb,
        String vmBridge,
        String storage,
        @Schema(description = "게스트 디스크가 놓이는 thin pool 용량(GB) — 아직 측정되지 않았으면 null")
        @Nullable Long diskCapacityGb,
        long runningVms,
        long allocatedVcpu,
        long allocatedMemoryMb,
        double cpuOvercommitRatio,
        double memoryAllocRatio,
        double cpuWarnThreshold,
        double memoryWarnThreshold,
        IpPoolSummaryResponse ipPool) {
}
