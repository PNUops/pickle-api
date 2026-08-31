package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Aggregate demand in one of the fixed 7, 30 and 90 day windows. */
public record LlmUsageWindowResponse(
        int days,
        long requests,
        long inputTokens,
        long outputTokens,
        long estimatedRequests,
        long tokenAxisRequests,
        long creditAxisRequests,
        long unknownAxisRequests,
        @Schema(description = "요청 당시 TOKEN/CREDIT 축이 기록된 request 비율. 요청이 없으면 null")
        @Nullable Double axisCoverage) {
}
