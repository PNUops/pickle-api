package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "사용 계획")
        @Nullable String usagePlan,

        @Schema(description = "희망 분당 요청 수")
        @Nullable Integer reqRpm,

        @Schema(description = "희망 분당 토큰 수")
        @Nullable Integer reqTpm,

        @Schema(description = "희망 일일 토큰 수")
        @Nullable Long reqDailyTokens,

        @Schema(description = "부여 분당 요청 수. 비어 있으면 서비스 기본값입니다.")
        @Nullable Integer grantedRpm,

        @Schema(description = "부여 분당 토큰 수. 비어 있으면 서비스 기본값입니다.")
        @Nullable Integer grantedTpm,

        @Schema(description = "부여 동시 요청 수. 비어 있으면 서비스 기본값입니다.")
        @Nullable Integer grantedConcurrency,

        @Schema(description = "부여 일일 토큰 수. 비어 있으면 서비스 기본값입니다.")
        @Nullable Long grantedDailyTokens) {

    public static LlmKeyRequestSpecResponse from(LlmKeyRequestDetail detail) {
        return new LlmKeyRequestSpecResponse(detail.getReqPurpose(), detail.getReqRpm(),
                detail.getReqTpm(), detail.getReqDailyTokens(), detail.getGrantedRpm(),
                detail.getGrantedTpm(), detail.getGrantedConcurrency(),
                detail.getGrantedDailyTokens());
    }
}
