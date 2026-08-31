package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
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

        @Schema(description = "부여 일일 토큰 수. 비우면 일일 한도가 없습니다. 0이면 자체 서빙(토큰) 축을 쓸 수 없습니다.")
        @Min(value = 0, message = "일일 토큰 수는 0 이상이어야 합니다.")
        @Nullable Long grantedDailyTokens,

        @Schema(description = "부여 금액 한도(USD 크레딧). 비우거나 0이면 상용(금액) 축을 쓸 수 없습니다.")
        @DecimalMin(value = "0", message = "금액 한도는 0 이상이어야 합니다.")
        @Digits(integer = 10, fraction = 2, message = "금액 한도는 소수점 둘째 자리까지 입력해 주세요.")
        @Nullable BigDecimal grantedCreditLimit,

        @Schema(description = "금액 한도 리셋 창. 비우면 리셋 없는 총액 상한입니다. 창은 UTC 자정에 초기화됩니다.")
        @Nullable CreditLimitReset grantedCreditLimitReset,

        @Schema(description = "금액 축에 사용할 기관 OpenRouter 사업 account. 하나뿐이면 생략할 수 있습니다.")
        @Nullable UUID openrouterAccountId) {
}
