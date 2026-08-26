package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyErrorType}: how the key's failed requests
 * failed, over the requested window.
 */
public record LlmKeyErrorTypeResponse(
        @Schema(description = """
                오류 종류. 게이트웨이가 종류를 남기지 않은 실패는 null이며, 화면에서는 \
                '기타'로 묶입니다.""")
        @Nullable String errorType,
        long requests) {
}
