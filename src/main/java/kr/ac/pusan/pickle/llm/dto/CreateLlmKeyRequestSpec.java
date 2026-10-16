package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import kr.ac.pusan.pickle.llm.PassthroughEndpoints;
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
        @Schema(description = "자체 서빙 모델을 쓸지. 비우면 사용으로 봅니다.")
        @Nullable Boolean useCampusModels,

        @Schema(description = "유료 모델을 쓸지. 비우면 사용하지 않는 것으로 봅니다.")
        @Nullable Boolean useCommercialModels,

        @Schema(description = "희망 금액 한도(USD). 유료 모델을 쓸 때만 적습니다.",
                example = "20.00")
        @DecimalMin(value = "0.01", message = "금액 한도는 0보다 커야 합니다.")
        @DecimalMax(value = "100000.00", message = "금액 한도가 너무 큽니다.")
        // 열이 numeric(12,2)이라 셋째 자리는 저장되면서 반올림된다. 막지 않으면
        // 0.015를 적은 사람이 0.02로 적힌 신청을 돌려받는다. 부여 쪽과 같은 규칙이다.
        @Digits(integer = 10, fraction = 2, message = "금액 한도는 소수점 둘째 자리까지 입력해 주세요.")
        @Nullable BigDecimal reqCreditLimit,

        @Schema(description = "희망 분당 요청 수. 비우면 서비스 기본값을 받습니다.")
        @Min(value = 1, message = "분당 요청 수는 1 이상이어야 합니다.")
        @Max(value = 10000, message = "분당 요청 수가 너무 큽니다.")
        @Nullable Integer reqRpm,

        @Schema(description = "희망 분당 토큰 수. 비우면 서비스 기본값을 받습니다.")
        @Min(value = 1, message = "분당 토큰 수는 1 이상이어야 합니다.")
        @Nullable Integer reqTpm,

        @Schema(description = "희망 일일 토큰 수. 비우면 서비스 기본값을 받습니다.")
        @Min(value = 1, message = "일일 토큰 수는 1 이상이어야 합니다.")
        @Nullable Long reqDailyTokens,

        /**
         * Required, and an empty list is a valid answer. A request that says
         * nothing is refused rather than read as some default, so what the
         * reviewer sees as asked for is always what the applicant chose.
         */
        @ArraySchema(schema = @Schema(allowableValues = {PassthroughEndpoints.CHAT,
                PassthroughEndpoints.WEB_SEARCH, PassthroughEndpoints.PDF_OCR,
                PassthroughEndpoints.CONTAINERS, PassthroughEndpoints.IMAGES,
                PassthroughEndpoints.EMBEDDINGS, PassthroughEndpoints.RERANK,
                PassthroughEndpoints.SPEECH, PassthroughEndpoints.TRANSCRIPTIONS,
                PassthroughEndpoints.VIDEOS, PassthroughEndpoints.BATCHES,
                PassthroughEndpoints.FILES}),
                arraySchema = @Schema(description = "신청자가 고른 기능 권한. 필수이며 빈 배열도 받습니다. "
                                + "승인 화면의 초깃값이 되고, 무엇을 부여할지는 승인자가 정합니다. "
                                + "지금 부여할 수 있는 값은 chat, web_search, pdf_ocr, images, "
                                + "embeddings이고 나머지는 422로 거절합니다. web_search와 pdf_ocr은 "
                                + "chat과 함께 골라야 합니다."))
        @NotNull(message = "필요한 기능을 골라 주세요. 아무 기능도 쓰지 않으려면 빈 배열을 보내 주세요.")
        @Size(max = 20, message = "기능은 최대 20개까지 고를 수 있습니다.")
        List<String> reqPassthroughEndpoints) {
}
