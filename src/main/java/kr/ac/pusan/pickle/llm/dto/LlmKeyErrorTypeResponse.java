package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyErrorType}: how the key's failed requests
 * failed, over the requested window.
 */
public record LlmKeyErrorTypeResponse(
        @Schema(description = """
                오류 유형이며, Gateway가 유형을 남기지 않은 실패는 null이에요.""")
        @Nullable String errorType,
        long requests) {
}
