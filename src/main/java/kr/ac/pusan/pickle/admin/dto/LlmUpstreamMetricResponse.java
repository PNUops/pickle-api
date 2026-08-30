package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Final-outcome aggregates for the last upstream recorded on each event. */
public record LlmUpstreamMetricResponse(
        @Schema(description = "등록된 upstream 공개 ID. 미등록 ref면 null; ORG에는 허용된 upstream 행만 반환")
        @Nullable UUID id,
        @Schema(description = "gateway 내부 ref. SYS 계층에만 반환하며 ORG에서는 null")
        @Nullable String ref,
        String name,
        long finalOutcomes,
        long succeeded,
        long timeoutOrError,
        @Schema(description = "최종 결과 중 TIMEOUT 또는 UPSTREAM_ERROR 비율(0..1)")
        double timeoutOrErrorRate,
        long inputTokens,
        long outputTokens,
        long attemptsKnown,
        long multiAttemptRequests,
        @Schema(description = "attempts가 기록된 결과 중 attempts > 1 비율(0..1)")
        double multiAttemptRate,
        @Schema(description = "attempts가 기록된 결과의 평균 시도 횟수(배수)")
        double attemptAmplification,
        long latencySamples,
        @Nullable Long latencyP50Ms,
        @Nullable Long latencyP95Ms,
        @Nullable Long latencyP99Ms) {
}
