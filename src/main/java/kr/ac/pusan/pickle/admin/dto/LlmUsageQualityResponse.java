package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Freshness and delivery evidence that conditions the usage numbers. */
@Schema(description = "사용량 숫자의 source와 delivery 상태. latestUsageReceivedAt은 API가 "
        + "마지막으로 event를 받은 시각일 뿐 completeness watermark가 아닙니다. Gateway queue와 "
        + "loss 수치는 전역 값이며 SYS가 기관으로 좁혀도 전역이고, ORG에는 null입니다.")
public record LlmUsageQualityResponse(
        @Nullable Instant rollupLastSuccessAt,
        @Schema(description = "API가 마지막 usage event를 받은 시각. Completeness watermark가 아님")
        @Nullable Instant latestUsageReceivedAt,
        @Schema(description = "양수 credit limit을 가진 key 수")
        long creditMetersTotal,
        @Schema(description = "양수 credit limit key 중 vendor meter 관측 이력이 있는 수")
        long creditMetersObserved,
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
        @Nullable Long unattributedRequests) {
}
