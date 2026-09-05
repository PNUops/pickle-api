package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
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

        @Schema(description = "이 키가 쓸 수 있는 유료 모델 목록. 비우면 제한이 없고, 자체 서빙 "
                + "모델은 이 목록과 무관합니다. 항목은 모델 이름 또는 벤더 프리픽스"
                + "(예: openai/*)입니다.")
        @Size(max = 50, message = "모델은 최대 50개까지 허용할 수 있습니다.")
        @Nullable List<String> grantedCreditAllowedModels,

        @Schema(description = "이 키가 쓸 수 없는 유료 모델 목록. 비우면 차단하는 모델이 없고, "
                + "허용 목록과 함께 걸리면 차단이 이깁니다. 허용 목록과 달리 금액 한도가 "
                + "0이어도 남습니다. 자체 서빙 모델은 이 목록과 무관합니다.")
        @Size(max = 50, message = "모델은 최대 50개까지 차단할 수 있습니다.")
        @Nullable List<String> grantedCreditDeniedModels,

        @Schema(description = "유료 모델을 결제할 기관 사업 계정. 하나뿐이면 생략할 수 있습니다.")
        @Nullable UUID openrouterAccountId) {
}
