package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.time.Instant;
import java.time.LocalDate;

/** Bounded list of keys whose configured limits or real pressure merit review. */
public record LlmLimitReviewCollectionResponse(
        List<LlmLimitReviewResponse> items,
        long totalItems,
        @Schema(description = "현재 items가 같은 한도 검토 모집단 전체를 담지 않았는지. 다음 페이지 여부는 page/totalPages로 확인합니다.")
        boolean truncated, int page, int size, int totalPages,
        LocalDate pressureFrom, LocalDate pressureTo, Instant configurationObservedAt) {
}
