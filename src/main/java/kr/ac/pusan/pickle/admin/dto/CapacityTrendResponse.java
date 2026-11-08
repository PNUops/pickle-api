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
        @Schema(description = "ACTIVE 노드의 thin pool 용량 합(GB). 오버프로비저닝을 전제로 한 참고용 분모이며 "
                + "용량 미등록 노드가 있으면 null")
        @Nullable Long capacityDiskGb,
        @Schema(description = "일별 스냅샷이며 해당 일 종료 시점에 살아 있던 VM의 할당 합계")
        List<CapacityTrendPointResponse> points) {
}
