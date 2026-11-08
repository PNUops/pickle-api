package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Freshness and delivery evidence that conditions the usage numbers. */
@Schema(description = "사용량 숫자의 source와 delivery 상태. latestUsageReceivedAt은 API가 "
        + "마지막으로 event를 받은 시각이며 수집 완료 경계를 보장하지 않아요. Gateway queue와 "
        + "누락 수치는 플랫폼 전역 값이에요. SYS가 기관으로 좁혀도 같은 값이며 ORG에는 null이에요.")
public record LlmUsageQualityResponse(
        @Nullable Instant rollupLastSuccessAt,
        @Schema(description = "API가 마지막 usage event를 받은 시각. Completeness watermark가 아님")
        @Nullable Instant latestUsageReceivedAt,
        @Schema(description = "양수 credit limit을 가진 key 수")
        @Nullable Long creditMetersTotal,
        @Schema(description = "양수 credit limit key 중 vendor meter 관측 이력이 있는 수")
        @Nullable Long creditMetersObserved,
        @Nullable Instant oldestCreditUsageAt,
        @Nullable Instant latestCreditUsageAt,
        long totalRequests,
        long estimatedRequests,
        @Nullable Double estimatedRequestRatio,
        long totalTokens,
        @Schema(description = "Estimated input+output token. 원본이 없는 bucket이 섞이면 null")
        @Nullable Long estimatedTokens,
        @Nullable Double estimatedTokenRatio,
        LlmGatewayReportState gatewayReportState,
        LlmGatewayReportState usageQueueReportState,
        @Nullable Instant lastContactAt,
        @Nullable Instant lastUsageShipSuccessAt,
        @Nullable Instant usageQueueObservedAt,
        @Nullable Instant oldestUnshippedEventAt,
        @Nullable Long queuedUsageEvents,
        @Nullable Long queuedUsageBytes,
        @Nullable Long spoolWriteFailures,
        @Nullable Long usageShipFailures,
        @Nullable Long usageQueueScanFailures,
        @Schema(description = "Key에 귀속되지 않은 selected-window request 수. SYS global에서만 값이 있음")
        @Nullable Long unattributedRequests,
        @Schema(description = """
                선택 기간에 예산 축과 관계없이 금액이 보고된 요청 수예요. 금융 정보가 미제공이면 null이에요. 이 값으로 실제 공급자나 부담 주체, 유료 요청의 금액 미보고 건수를 판단하지 않아요.""")
        @Nullable Long pricedRequests,
        @Schema(description = """
                선택 기간에 경로가 기록된 요청 수예요. 경로 항목이 추가된 2026-09-06 전의 요청은 포함하지 않아 경로별 분해 범위가 더 작을 수 있어요.""")
        long endpointRecordedRequests) {
}
