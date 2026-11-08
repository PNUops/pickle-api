package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.PassthroughEndpoints;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code ApproveLlmKeyRequestSpec}: what the reviewer grants an
 * LLM API key request.
 *
 * <p>RPM, TPM and concurrency use the gateway's defaults when absent. A null
 * daily token allowance is unlimited; zero closes the token budget axis.
 */
public record ApproveLlmKeyRequestSpec(
        @Schema(description = "부여할 분당 요청 수이며, 생략하거나 null이면 서비스 기본값을 적용해요.")
        @Min(value = 1, message = "분당 요청 수는 1 이상으로 입력해 주세요.")
        @Max(value = 10000, message = "분당 요청 수는 10000 이하로 입력해 주세요.")
        @Nullable Integer grantedRpm,

        @Schema(description = "부여할 분당 토큰 수이며, 생략하거나 null이면 서비스 기본값을 적용해요.")
        @Min(value = 1, message = "분당 토큰 수는 1 이상으로 입력해 주세요.")
        @Nullable Integer grantedTpm,

        @Schema(description = "부여할 동시 요청 수이며, 생략하거나 null이면 서비스 기본값을 적용해요.")
        @Min(value = 1, message = "동시 요청 수는 1 이상으로 입력해 주세요.")
        @Max(value = 100, message = "동시 요청 수는 100 이하로 입력해 주세요.")
        @Nullable Integer grantedConcurrency,

        @Schema(description = "부여할 일일 토큰 수예요. 생략하거나 null이면 일일 한도가 없고, 0이면 자체 서빙(토큰) 축을 쓸 수 없어요.")
        @Min(value = 0, message = "일일 토큰 수는 0 이상으로 입력해 주세요.")
        @Nullable Long grantedDailyTokens,

        @Schema(description = "키 하나에 부여할 금액 한도예요(USD 크레딧). 생략하거나 null이나 0을 보내면 유료 모델을 쓸 수 없어요.")
        @DecimalMin(value = "0", message = "금액 한도는 0 이상으로 입력해 주세요.")
        @Digits(integer = 10, fraction = 2, message = "금액 한도는 소수점 둘째 자리까지 입력해 주세요.")
        @Nullable BigDecimal grantedCreditLimit,

        @Schema(description = "금액 한도 리셋 창이에요. 생략하거나 null이면 리셋 없는 총액 상한이며, 리셋 창은 UTC 자정을 기준으로 해요.")
        @Nullable CreditLimitReset grantedCreditLimitReset,

        @Schema(description = "이 키의 유료 모델 허용 목록이에요. 생략하거나 비어 있으면 이 목록으로 제한하지 않아요. "
                + "자체 서빙 모델에는 적용하지 않아요."
                + CreditModelPatterns.ALLOW_PATTERN_DESCRIPTION)
        @Size(max = 50, message = "허용할 모델은 50개 이내로 지정해 주세요.")
        @Nullable List<String> grantedCreditAllowedModels,

        @Schema(description = "이 키의 유료 모델 차단 목록이에요. 생략하거나 비어 있으면 이 목록으로 차단하지 않아요. "
                + "허용 목록과 겹치면 차단을 적용하며, 허용 목록과 달리 금액 한도가 "
                + "0이어도 유지돼요. 자체 서빙 모델에는 적용하지 않아요."
                + CreditModelPatterns.DENY_PATTERN_DESCRIPTION)
        @Size(max = 50, message = "차단할 모델은 50개 이내로 지정해 주세요.")
        @Nullable List<String> grantedCreditDeniedModels,

        @ArraySchema(schema = @Schema(allowableValues = {PassthroughEndpoints.CHAT,
                PassthroughEndpoints.WEB_SEARCH, PassthroughEndpoints.PDF_OCR,
                PassthroughEndpoints.CONTAINERS, PassthroughEndpoints.IMAGES,
                PassthroughEndpoints.EMBEDDINGS, PassthroughEndpoints.RERANK,
                PassthroughEndpoints.SPEECH, PassthroughEndpoints.TRANSCRIPTIONS,
                PassthroughEndpoints.VIDEOS, PassthroughEndpoints.BATCHES,
                PassthroughEndpoints.FILES}),
                arraySchema = @Schema(description = "이 키에 부여할 기능 권한이에요. 생략하거나 null이면 "
                        + "신청 목록(reqPassthroughEndpoints)으로 승인하며, 빈 배열이면 "
                        + "chat을 포함해 어떤 기능도 쓸 수 없어요. "
                        + "현재 부여할 수 있는 값은 chat, web_search, pdf_ocr, "
                        + "images, embeddings, rerank, speech, transcriptions, batches이며, web_search나 pdf_ocr를 포함하면 chat도 함께 "
                        + "부여해야 하고, batches는 chat이나 embeddings와 함께 부여해야 해요. 모델 조회에는 이 목록을 적용하지 않아요."))
        @Size(max = 20, message = "허용할 기능은 20개 이내로 지정해 주세요.")
        @Nullable List<String> grantedPassthroughEndpoints,

        @Schema(description = "유료 모델을 결제할 기관 사업 계정이에요. 연결 가능한 계정이 하나뿐이면 생략할 수 있어요.")
        @Nullable UUID openrouterAccountId) {
}
