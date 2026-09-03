package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
        /**
         * 어느 축을 쓰겠다는 것인지. 한도가 비어 있는 것으로는 알 수 없다 -- 빈 한도는
         * "서비스 기본값"이라는 뜻이지 "그 축은 안 쓴다"는 뜻이 아니다.
         */
        @Schema(description = "교내 자체 서빙 모델을 쓸지. 비우면 사용으로 봅니다.")
        @Nullable Boolean useCampusModels,

        @Schema(description = "유료(상용) 모델을 쓸지. 비우면 사용하지 않는 것으로 봅니다.")
        @Nullable Boolean useCommercialModels,

        @Schema(description = "희망 금액 한도(USD). 유료 모델을 쓸 때만 적습니다.",
                example = "20.00")
        @DecimalMin(value = "0.01", message = "금액 한도는 0보다 커야 합니다.")
        @DecimalMax(value = "100000.00", message = "금액 한도가 너무 큽니다.")
        @Nullable BigDecimal reqCreditLimit,

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
