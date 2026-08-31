package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One exact gateway limit-rejection reason observed during the last seven KST days. */
public record LlmLimitPressureResponse(
        @Schema(allowableValues = {"quota_exhausted", "credit_exhausted",
                "rate_limit_requests", "rate_limit_tokens", "rate_limit_concurrency"})
        String reason,
        long requests) {
}
