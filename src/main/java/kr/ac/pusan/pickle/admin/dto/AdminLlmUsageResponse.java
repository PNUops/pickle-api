package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

/** Decision-oriented administrator LLM usage read model. */
public record AdminLlmUsageResponse(
        Instant generatedAt,
        @Schema(description = "모든 일자 경계와 오늘 사용량에 적용한 시간대")
        String timezone,
        LocalDate from,
        LocalDate to,
        int days,
        LlmUsageDemandResponse demand,
        LlmUsageConsumersResponse consumers,
        LlmLimitReviewCollectionResponse limitReview,
        LlmUsageQualityResponse quality) {
}
