package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Contract schema {@code LlmKeyHourlyUsage}: one cell of the weekday-by-hour
 * distribution, in KST. Only cells with traffic are sent; the client fills the
 * rest of the grid with zeroes.
 */
public record LlmKeyHourlyUsageResponse(
        @Schema(description = "요일. 1=월요일 … 7=일요일(ISO), KST 기준.")
        int weekday,
        @Schema(description = "시각. 0~23, KST 기준.")
        int hour,
        long requests) {
}
