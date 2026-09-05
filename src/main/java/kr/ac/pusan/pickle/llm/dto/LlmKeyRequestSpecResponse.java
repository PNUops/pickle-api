package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmKeyRequestDetail;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyRequestSpec}: what an LLM API key request asked
 * for and what the reviewer granted of it.
 *
 * <p>Every number is optional on both sides. Null on the requested side means
 * the applicant did not ask for a particular limit; null on the granted side
 * means the reviewer granted the service defaults — which is the ordinary
 * decision, not an omission.
 */
public record LlmKeyRequestSpecResponse(
        @Schema(description = "희망 분당 요청 수")
        @Nullable Integer reqRpm,

        @Schema(description = "희망 분당 토큰 수")
        @Nullable Integer reqTpm,

        @Schema(description = "희망 일일 토큰 수")
        @Nullable Long reqDailyTokens,

        @Schema(description = "신청자가 자체 서빙 모델을 쓰겠다고 했는지.")
        boolean useCampusModels,

        @Schema(description = "신청자가 유료 모델을 쓰겠다고 했는지.")
        boolean useCommercialModels,

        @Schema(description = "신청자가 요청한 금액 한도(USD). 유료를 쓰지 않는 신청은 비어 있습니다.")
        @Nullable BigDecimal reqCreditLimit,

        @Schema(description = "부여 분당 요청 수. 비어 있으면 서비스 기본값입니다.")
        @Nullable Integer grantedRpm,

        @Schema(description = "부여 분당 토큰 수. 비어 있으면 서비스 기본값입니다.")
        @Nullable Integer grantedTpm,

        @Schema(description = "부여 동시 요청 수. 비어 있으면 서비스 기본값입니다.")
        @Nullable Integer grantedConcurrency,

        @Schema(description = "부여 일일 토큰 수. 비어 있으면 일일 한도가 없습니다. 0이면 자체 서빙(토큰) 축을 쓸 수 없습니다.")
        @Nullable Long grantedDailyTokens,

        @Schema(description = "부여 금액 한도(USD 크레딧). 비어 있거나 0이면 상용(금액) 축을 쓸 수 없습니다.")
        @Nullable BigDecimal grantedCreditLimit,

        @Schema(description = "금액 한도 리셋 창. 비어 있으면 리셋 없는 총액 상한입니다.")
        @Nullable CreditLimitReset grantedCreditLimitReset,

        @Schema(description = "부여된 상용(금액) 축 모델 허용 목록. 빈 배열이면 제한이 없습니다. "
                + "어떤 모델을 열지는 신청자가 요구하는 값이 아니라 승인자가 정하는 값이라 "
                + "희망 쪽 짝이 없습니다.")
        List<String> grantedCreditAllowedModels,

        @Schema(description = "부여된 상용(금액) 축 모델 차단 목록. 빈 배열이면 차단이 없습니다. "
                + "허용 목록과 함께 걸리면 차단이 이깁니다.")
        List<String> grantedCreditDeniedModels) {

    public static LlmKeyRequestSpecResponse from(LlmKeyRequestDetail detail,
            List<String> grantedCreditAllowedModels, List<String> grantedCreditDeniedModels) {
        return new LlmKeyRequestSpecResponse(detail.getReqRpm(),
                detail.getReqTpm(), detail.getReqDailyTokens(), detail.isReqUseCampus(),
                detail.isReqUseCommercial(), detail.getReqCreditLimit(), detail.getGrantedRpm(),
                detail.getGrantedTpm(), detail.getGrantedConcurrency(),
                detail.getGrantedDailyTokens(), detail.getGrantedCreditLimit(),
                detail.getGrantedCreditLimitReset(), grantedCreditAllowedModels,
                grantedCreditDeniedModels);
    }
}
