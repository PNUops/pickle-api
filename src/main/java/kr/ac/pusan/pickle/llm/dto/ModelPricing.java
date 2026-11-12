package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Every price axis the vendor publishes for one model, not only input and
 * output.
 *
 * <p>Both the approval picker and a key holder's model list read this, which is
 * why it lives here rather than beside either response. The two flat prices
 * those responses already carry stay where they are; this is the rest of the
 * bill, and it repeats those two so a reader does not have to merge sources.
 */
@Schema(description = "모델의 전체 가격 항목이며, 벤더가 공시한 입력과 출력 등의 항목을 모두 담아요.")
public record ModelPricing(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "기본 가격 항목이며, 0은 해당 항목이 무료라는 뜻이에요.")
        List<ModelPriceAxis> axes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "조건이 맞을 때 기본 가격 대신 적용하는 구간 가격이며, 없으면 빈 배열이에요.")
        List<ModelPriceTier> tiers) {

    /** How the price of one axis is counted. */
    @Schema(description = "가격 단위예요. PER_MILLION_TOKENS는 100만 토큰당, PER_CALL은 해당 항목이 세는 호출 1회당 USD예요."
            + " web_search는 검색 1회, request는 API 요청 1회를 뜻해요. UNKNOWN은 단위를 알 수 없어 벤더 값을 그대로 담아요.")
    public enum ModelPriceUnit {
        PER_MILLION_TOKENS,
        /**
         * One occurrence of whatever the axis counts. For {@code web_search}
         * that is one search, and a single request can run several.
         */
        PER_CALL,
        /**
         * An axis this code has no unit for. Sent rather than dropped: an
         * approver who sees an unnamed charge can ask about it, one who sees
         * nothing cannot.
         */
        UNKNOWN
    }

    @Schema(description = "가격 축 하나.")
    public record ModelPriceAxis(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "벤더가 쓰는 축 이름.", example = "input_cache_read")
            String axis,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "가격 단위.")
            ModelPriceUnit unit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "가격이에요(USD). 단위는 unit을 따라요.")
            BigDecimal price) {
    }

    /**
     * One conditional price band. Every condition present must hold; an absent
     * one does not narrow the band.
     */
    @Schema(description = "구간 가격이며, 값이 있는 조건이 모두 맞을 때 적용해요.")
    public record ModelPriceTier(
            @Schema(description = "입력 토큰이 이 값을 넘는 요청에 적용해요.")
            @Nullable Integer minPromptTokens,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "적용 요일이에요(UTC, 영어 소문자). 빈 배열이면 요일 조건이 없어요.",
                    example = "[\"saturday\", \"sunday\"]")
            List<String> utcDays,
            @Schema(description = "적용 시작 시각이며, UTC 자정부터 센 분이에요.")
            @Nullable Integer utcStartMinute,
            @Schema(description = "적용 끝 시각(제외)이며 UTC 자정부터 센 분이에요. 1440은 다음 날 자정이에요.")
            @Nullable Integer utcEndMinute,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "해석하지 못한 벤더 조건 이름이에요. 비어 있지 않으면 "
                            + "앞의 조건만으로는 적용 범위를 모두 알 수 없어요.")
            List<String> otherConditions,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "이 구간에서 바뀌는 가격 축.")
            List<ModelPriceAxis> axes) {
    }
}
