package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmKeyRequestDetail;
import kr.ac.pusan.pickle.llm.PassthroughEndpoints;
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

        @Schema(description = "신청에 자체 서빙 모델 사용을 포함하는지")
        boolean useCampusModels,

        @Schema(description = "신청에 유료 모델 사용을 포함하는지")
        boolean useCommercialModels,

        @Schema(description = "요청한 금액 한도예요(USD). 유료 모델을 신청하지 않았으면 null이에요.")
        @Nullable BigDecimal reqCreditLimit,

        @ArraySchema(schema = @Schema(allowableValues = {PassthroughEndpoints.CHAT,
                PassthroughEndpoints.WEB_SEARCH, PassthroughEndpoints.PDF_OCR,
                PassthroughEndpoints.CONTAINERS, PassthroughEndpoints.IMAGES,
                PassthroughEndpoints.EMBEDDINGS, PassthroughEndpoints.RERANK,
                PassthroughEndpoints.SPEECH, PassthroughEndpoints.TRANSCRIPTIONS,
                PassthroughEndpoints.VIDEOS, PassthroughEndpoints.BATCHES,
                PassthroughEndpoints.FILES}),
                arraySchema = @Schema(description = "요청된 기능 권한이며 승인 화면의 "
                        + "초깃값이에요. 이 항목이 생기기 전의 신청은 [\"chat\"]으로 이관됐어요."))
        List<String> reqPassthroughEndpoints,

        @Schema(description = "부여한 분당 요청 수예요. 승인 후 값이 없으면 서비스 기본값이에요.")
        @Nullable Integer grantedRpm,

        @Schema(description = "부여한 분당 토큰 수예요. 승인 후 값이 없으면 서비스 기본값이에요.")
        @Nullable Integer grantedTpm,

        @Schema(description = "부여한 동시 요청 수예요. 승인 후 값이 없으면 서비스 기본값이에요.")
        @Nullable Integer grantedConcurrency,

        @Schema(description = "부여한 일일 토큰 수예요. 승인 후 값이 없으면 일일 한도가 없고, 0이면 자체 서빙(토큰) 축을 쓸 수 없어요.")
        @Nullable Long grantedDailyTokens,

        @Schema(description = "부여한 금액 한도예요(USD 크레딧). 승인 후 값이 없거나 0이면 유료 모델을 쓸 수 없어요.")
        @Nullable BigDecimal grantedCreditLimit,

        @Schema(description = "금액 한도 리셋 창이에요. 승인 후 값이 없으면 리셋 없는 총액 상한이에요.")
        @Nullable CreditLimitReset grantedCreditLimitReset,

        @Schema(description = "승인자가 부여한 유료 모델 허용 목록이에요. "
                + "빈 배열이면 이 목록으로 "
                + "제한하지 않아요."
                + CreditModelPatterns.ALLOW_PATTERN_DESCRIPTION)
        List<String> grantedCreditAllowedModels,

        @Schema(description = "승인자가 부여한 유료 모델 차단 목록이며, 빈 배열이면 이 목록으로 차단하지 않아요. "
                + "허용 목록과 겹치면 차단을 적용해요."
                + CreditModelPatterns.DENY_PATTERN_DESCRIPTION)
        List<String> grantedCreditDeniedModels,
        @ArraySchema(schema = @Schema(allowableValues = {PassthroughEndpoints.CHAT,
                PassthroughEndpoints.WEB_SEARCH, PassthroughEndpoints.PDF_OCR,
                PassthroughEndpoints.CONTAINERS, PassthroughEndpoints.IMAGES,
                PassthroughEndpoints.EMBEDDINGS, PassthroughEndpoints.RERANK,
                PassthroughEndpoints.SPEECH, PassthroughEndpoints.TRANSCRIPTIONS,
                PassthroughEndpoints.VIDEOS, PassthroughEndpoints.BATCHES,
                PassthroughEndpoints.FILES}),
                arraySchema = @Schema(description = "승인자가 부여한 기능 권한이며, 빈 배열이면 "
                        + "부여한 기능이 없어요. 검토 전 신청은 빈 배열이에요."))
        List<String> grantedPassthroughEndpoints) {

    public static LlmKeyRequestSpecResponse from(LlmKeyRequestDetail detail,
            List<String> reqPassthroughEndpoints, List<String> grantedCreditAllowedModels, List<String> grantedCreditDeniedModels,
            List<String> grantedPassthroughEndpoints) {
        return new LlmKeyRequestSpecResponse(detail.getReqRpm(),
                detail.getReqTpm(), detail.getReqDailyTokens(), detail.isReqUseCampus(),
                detail.isReqUseCommercial(), detail.getReqCreditLimit(), reqPassthroughEndpoints,
                detail.getGrantedRpm(),
                detail.getGrantedTpm(), detail.getGrantedConcurrency(),
                detail.getGrantedDailyTokens(), detail.getGrantedCreditLimit(),
                detail.getGrantedCreditLimitReset(), grantedCreditAllowedModels,
                grantedCreditDeniedModels, grantedPassthroughEndpoints);
    }
}
