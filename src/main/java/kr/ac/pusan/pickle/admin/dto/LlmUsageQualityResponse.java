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
        @Nullable Long unattributedRequests,
        @Schema(description = """
                이 기간 요청 중 공급자가 금액을 알려 준 수. 자체 서빙 요청은 금액이라는 \
                것이 없어 여기 들어가지 않으므로, 이 값이 작은 것 자체는 결함이 아닙니다. \
                **totalRequests와 견주지 마십시오** — 그 비율은 「금액을 못 받은 비율」이 \
                아니라 자체 서빙 비중을 함께 담습니다. 이 요약 층에는 유료 요청 수가 없고, \
                그것이 필요하면 소비처와 호출 종류 행의 creditAxisRequests를 씁니다.""")
        long pricedRequests,
        @Schema(description = """
                이 기간 요청 중 들어온 경로가 기록된 수. 경로 축은 2026-09-06에 생겼으므로 \
                그 전 요청은 여기 들어가지 않고, 경로별 분해도 그만큼 덜 덮습니다.""")
        long endpointRecordedRequests) {
}
