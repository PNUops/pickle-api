package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code CreateLlmKeyRequestSpec}: what an LLM API key request
 * asks for beyond the fields every request has.
 *
 * <p>Every limit here is optional. A key that names none is asking for the
 * service's defaults, which is what most requests are; the fields exist for the
 * case where somebody knows they need more and has to say why.
 */
public record CreateLlmKeyRequestSpec(
        @Schema(description = "이 Key를 어디에 쓸지. 기본 한도로 충분하면 비워 두어도 됩니다.")
        @Size(max = 2000, message = "사용 계획은 2000자 이하여야 합니다.")
        @Nullable String usagePlan,

        @Schema(description = "희망 분당 요청 수. 비우면 서비스 기본값을 받습니다.")
        @Min(value = 1, message = "분당 요청 수는 1 이상이어야 합니다.")
        @Max(value = 10000, message = "분당 요청 수가 너무 큽니다.")
        @Nullable Integer reqRpm,

        @Schema(description = "희망 분당 토큰 수. 비우면 서비스 기본값을 받습니다.")
        @Min(value = 1, message = "분당 토큰 수는 1 이상이어야 합니다.")
        @Nullable Integer reqTpm,

        @Schema(description = "희망 일일 토큰 수. 비우면 서비스 기본값을 받습니다.")
        @Min(value = 1, message = "일일 토큰 수는 1 이상이어야 합니다.")
        @Nullable Long reqDailyTokens) {
}
