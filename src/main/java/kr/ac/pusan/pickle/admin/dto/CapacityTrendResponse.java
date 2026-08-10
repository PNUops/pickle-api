package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code CapacityTrend}: allocation per day against today's
 * physical capacity. The capacity figures are a present-tense reference line,
 * not a history — the nodes table keeps no record of what it used to hold.
 */
public record CapacityTrendResponse(
        LocalDate from,
        LocalDate to,
        long capacityCpuThreads,
        long capacityMemoryMb,
        @Schema(description = "ACTIVE 노드의 thin pool 용량 합(GB) — 오버프로비저닝 전제의 조언용 분모, "
                + "용량 미등록 노드가 있으면 null")
        @Nullable Long capacityDiskGb,
        @Schema(description = "일 단위 스냅샷 — 해당 일 종료 시점에 살아 있던 VM의 할당 합")
        List<CapacityTrendPointResponse> points) {
}
