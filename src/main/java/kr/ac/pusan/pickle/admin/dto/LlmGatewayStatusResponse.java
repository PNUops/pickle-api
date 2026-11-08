package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Gateway heartbeat and coarse queue freshness for all admins; detailed diagnostics are SYS-only. */
@Schema(description = "LLM gateway heartbeat와 usage 전송 상태. reportState, usageQueueReportState와 "
        + "lastContactAt은 이 조회가 허용된 관리자에게 제공해요. queue 수치와 누락 counter 등 나머지 "
        + "진단 필드는 SYS 계층에만 제공하며 ORG 계층에서는 null이에요.")
public record LlmGatewayStatusResponse(
        @Schema(description = "5초 sync heartbeat의 보고 신선도")
        LlmGatewayReportState reportState,
        @Schema(description = "usage queue 관측 신선도. 관측 시각 없음은 NOT_REPORTED, "
                + "10분 초과는 STALE, 그 이하는 FRESH")
        LlmGatewayReportState usageQueueReportState,
        @Schema(description = "API가 제공하려는 authorization document 세대. SYS 전용이며 ORG에서는 null")
        @Nullable Long desiredGeneration,
        @Schema(description = "Gateway가 적용했다고 보고한 세대. SYS 전용이며 미보고이거나 ORG면 null")
        @Nullable Long appliedGeneration,
        @Schema(description = "Gateway가 지원하는 authorization document 형식. SYS 전용이며 미보고이거나 ORG면 null")
        @Nullable Integer supportedFormat,
        @Schema(description = "Gateway binary 버전. SYS 전용이며 구 Gateway이거나 ORG면 null")
        @Nullable String agentVersion,
        @Schema(description = "Gateway 프로세스 시작 시각. SYS 전용이며 미보고이거나 ORG면 null")
        @Nullable Instant startedAt,
        @Schema(description = "gateway가 보고한 처리 중 요청 수. SYS 전용이며 ORG에서는 null")
        @Nullable Integer inFlight,
        @Schema(description = "Gateway 동시 처리 상한. SYS 전용이며 미보고이거나 ORG면 null")
        @Nullable Integer maxInFlight,
        @Schema(description = "authorization 문서에서 거절한 항목의 누적 수. SYS 전용이며 구 Gateway이거나 ORG면 null")
        @Nullable Integer rejectedEntries,
        @Schema(description = "authorization reload 실패 누적 수. SYS 전용이며 구 Gateway이거나 ORG면 null")
        @Nullable Long reloadFailures,
        @Schema(description = "gateway가 보고한 마지막 오류 요약. SYS 전용이며 없거나 ORG이면 null")
        @Nullable String lastError,
        @Schema(description = "본문 전달 포기 누적 수. SYS 전용이며 구 Gateway이거나 ORG면 null")
        @Nullable Long bodiesDropped,
        @Schema(description = "usage batch 전송 실패 누적 수. SYS 전용이며 구 Gateway이거나 ORG면 null")
        @Nullable Long usageShipFailures,
        @Schema(description = "usage event spool 기록 실패 누적 수. SYS 전용이며 구 Gateway이거나 ORG면 null")
        @Nullable Long spoolWriteFailures,
        @Schema(description = "API가 마지막 sync 요청을 받은 시각. 한 번도 접촉하지 않았으면 null")
        @Nullable Instant lastContactAt,
        @Schema(description = "마지막 usage 전송 성공 시각. SYS 전용이며 성공 기록이 없거나 ORG면 null")
        @Nullable Instant lastUsageShipSuccessAt,
        @Schema(description = "미전송 queue의 가장 오래된 event 시각. SYS 전용이며 queue가 비었거나 미관측, ORG면 null")
        @Nullable Instant oldestUnshippedEventAt,
        @Schema(description = "usage queue event 수. SYS 전용이며 관측했고 queue가 비었으면 0, 미관측이거나 ORG면 null")
        @Nullable Long queuedUsageEvents,
        @Schema(description = "usage queue byte 수. SYS 전용이며 관측했고 queue가 비었으면 0, 미관측이거나 ORG면 null")
        @Nullable Long queuedUsageBytes,
        @Schema(description = "usage queue를 마지막으로 온전히 조사한 시각. SYS 전용이며 구 Gateway이거나 ORG면 null")
        @Nullable Instant usageQueueObservedAt,
        @Schema(description = "usage queue 조사 실패 누적 수. SYS 전용이며 관측했고 실패가 없으면 0, 미관측이거나 ORG면 null")
        @Nullable Long usageQueueScanFailures) {
}
