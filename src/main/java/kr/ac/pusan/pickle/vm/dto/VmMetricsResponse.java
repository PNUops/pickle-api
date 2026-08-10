package kr.ac.pusan.pickle.vm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmMetrics}. A VM with no hypervisor guest behind it
 * yet is not an error: {@code available} is false, {@code points} is empty and
 * the console shows the reason instead of an empty chart.
 */
public record VmMetricsResponse(
        String timeframe,
        Instant fetchedAt,
        boolean available,
        @Schema(description = "NOT_PROVISIONED = 아직 프로비저닝되지 않았거나 삭제된 VM")
        @Nullable String unavailableReason,
        List<VmMetricPointResponse> points) {
}
