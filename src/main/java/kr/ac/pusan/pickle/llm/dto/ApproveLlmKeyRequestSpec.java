package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code ApproveLlmKeyRequestSpec}: what the reviewer grants an
 * LLM API key request.
 *
 * <p>All four are optional, and that is the ordinary approval: a key with no
 * limits of its own runs on the gateway's defaults, which is what the service
 * is tuned for. Granting a number here is the exception a reviewer makes
 * deliberately, so leaving the form untouched must mean "the usual", not "no
 * limit at all".
 */
public record ApproveLlmKeyRequestSpec(
        @Schema(description = "부여 분당 요청 수. 비우면 서비스 기본값이 적용됩니다.")
        @Min(value = 1, message = "분당 요청 수는 1 이상이어야 합니다.")
        @Max(value = 10000, message = "분당 요청 수가 너무 큽니다.")
        @Nullable Integer grantedRpm,

        @Schema(description = "부여 분당 토큰 수. 비우면 서비스 기본값이 적용됩니다.")
        @Min(value = 1, message = "분당 토큰 수는 1 이상이어야 합니다.")
        @Nullable Integer grantedTpm,

        @Schema(description = "부여 동시 요청 수. 비우면 서비스 기본값이 적용됩니다.")
        @Min(value = 1, message = "동시 요청 수는 1 이상이어야 합니다.")
        @Max(value = 100, message = "동시 요청 수가 너무 큽니다.")
        @Nullable Integer grantedConcurrency,

        @Schema(description = "부여 일일 토큰 수. 비우면 서비스 기본값이 적용됩니다.")
        @Min(value = 1, message = "일일 토큰 수는 1 이상이어야 합니다.")
        @Nullable Long grantedDailyTokens) {
}
