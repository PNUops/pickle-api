package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Raw-event aggregates over a bounded diagnostic window. */
public record LlmMetricsResponse(
        Instant from,
        Instant to,
        long totalEvents,
        long attributedEvents,
        @Schema(description = "upstreamRef가 기록된 event 비율(0..1)")
        double attributionCoverage,
        long attemptsKnownEvents,
        @Schema(description = "attempts가 기록된 event 비율(0..1)")
        double attemptCoverage,
        long estimatedEvents,
        @Schema(description = "토큰 수가 추정치인 event 비율(0..1)")
        double estimatedCoverage,
        List<LlmUpstreamMetricResponse> upstreams,
        List<LlmLocalRejectionMetricResponse> localRejections) {
}
