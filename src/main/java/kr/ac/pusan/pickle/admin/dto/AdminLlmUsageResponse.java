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
        @Schema(description = """
                같은 기간의 모델별과 경로별 사용량, 기능 권한의 사용 현황이에요.""")
        LlmUsageBreakdownResponse breakdown,
        LlmLimitReviewCollectionResponse limitReview,
        LlmUsageQualityResponse quality) {
}
