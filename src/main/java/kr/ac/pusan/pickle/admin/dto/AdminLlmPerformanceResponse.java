package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read-only, scoped performance aggregates without financial projections. */
public record AdminLlmPerformanceResponse(
        @Schema(description = "7, 30, 90일 중 선택한 기간이에요. null이면 보관 중인 전체 기간이에요.")
        @Nullable Integer days,
        @Schema(description = "전체 기간에서는 권한 범위에 남아 있는 첫 기록 일자예요. 기록이 없으면 null이에요.")
        @Nullable LocalDate from,
        LocalDate to,
        String timezone,
        LlmPerformanceTotals totals,
        LlmPerformancePopulation population,
        LlmPerformanceGranularity granularity,
        List<LlmPerformanceTrendPoint> trend,
        List<LlmPerformanceModelUsage> models,
        long otherModelRequests,
        LlmPerformanceConsumers consumers,
        @Nullable Instant rollupLastSuccessAt,
        LlmGatewayReportState gatewayReportState,
        LlmGatewayReportState usageQueueReportState) {

    @Schema(name = "LlmPerformanceTotals")
    public record LlmPerformanceTotals(long requests, long inputTokens, long outputTokens,
            long estimatedRequests,
            @Schema(description = "성공한 요청에 기록된 모델 이름의 종류 수예요.")
            long usedModelCount,
            @Schema(description = "입력 토큰에 이미 포함된 캐시 입력 토큰이에요.")
            long cachedInputTokens,
            @Schema(description = "출력 토큰에 이미 포함된 추론 토큰이에요.")
            long reasoningTokens,
            long streamedRequests) {
    }

    @Schema(name = "LlmPerformancePopulation", description = "선택 기간에 요청이 기록된 기관과 워크스페이스, 키 수예요. 개인 이용자 수가 아니에요.")
    public record LlmPerformancePopulation(long institutionCount, long workspaceCount, long keyCount) {
    }

    @Schema(name = "LlmPerformanceGranularity")
    public enum LlmPerformanceGranularity { DAY, MONTH }

    @Schema(name = "LlmPerformanceTrendPoint")
    public record LlmPerformanceTrendPoint(LocalDate periodStart, long requests, long inputTokens,
            long outputTokens) {
    }

    @Schema(name = "LlmPerformanceModelUsage")
    public record LlmPerformanceModelUsage(@Nullable String publicModelName, long requests,
            long inputTokens, long outputTokens) {
    }

    @Schema(name = "LlmPerformanceConsumers")
    public record LlmPerformanceConsumers(LlmUsageConsumerLevel level, List<LlmPerformanceConsumer> items,
            long totalItems,
            @Schema(description = "상위 목록 밖의 다른 귀속 소비처에 기록된 요청 수예요.")
            long otherRequests,
            @Schema(description = "키나 기관, 워크스페이스에 연결되지 않아 소비처 목록에 포함하지 못한 요청 수예요.")
            long unattributedRequests) {
    }

    @Schema(name = "LlmPerformanceConsumer")
    public record LlmPerformanceConsumer(UUID id, String name, long requests, long inputTokens,
            long outputTokens) {
    }
}
