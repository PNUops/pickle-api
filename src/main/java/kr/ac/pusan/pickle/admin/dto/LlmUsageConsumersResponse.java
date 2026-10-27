package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.time.LocalDate;

/** One bounded level of the scope-aware consumption drill-down. */
public record LlmUsageConsumersResponse(
        LlmUsageConsumerLevel level,
        List<LlmUsageConsumerResponse> items,
        long totalItems,
        @Schema(description = "이 응답의 items가 같은 조건의 전체 모집단을 모두 담지 않았는지. 다음 페이지 존재 여부와 다르며 page/totalPages로 탐색합니다.")
        boolean truncated, int page, int size, int totalPages,
        LocalDate from, LocalDate to, String source, LlmConsumerSort sort) {
}
