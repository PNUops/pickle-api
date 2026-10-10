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
@Schema(description = "모델의 가격 축 전체. 벤더가 공시한 축을 모두 담으며 입력과 출력도 포함합니다.")
public record ModelPricing(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "기본 가격 축. 0은 그 축이 무료라는 뜻입니다.")
        List<ModelPriceAxis> axes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "조건이 맞을 때 기본 가격 대신 적용되는 구간 가격. 없으면 비어 있습니다.")
        List<ModelPriceTier> tiers) {

    /** How the price of one axis is counted. */
    @Schema(description = "가격 단위. PER_MILLION_TOKENS는 100만 토큰당, PER_CALL은 그 축이 세는 호출 1회당 USD입니다"
            + "(web_search는 검색 1회, request는 API 요청 1회). UNKNOWN은 단위를 알 수 없어 벤더 값을 그대로 담은 것입니다.")
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
                    description = "가격(USD). 단위는 unit을 따릅니다.")
            BigDecimal price) {
    }

    /**
     * One conditional price band. Every condition present must hold; an absent
     * one does not narrow the band.
     */
    @Schema(description = "구간 가격 하나. 값이 있는 조건이 모두 맞을 때 적용됩니다.")
    public record ModelPriceTier(
            @Schema(description = "입력 토큰이 이 값을 넘는 요청에 적용됩니다.")
            @Nullable Integer minPromptTokens,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "적용 요일(UTC, 영어 소문자). 비어 있으면 요일 조건이 없습니다.",
                    example = "[\"saturday\", \"sunday\"]")
            List<String> utcDays,
            @Schema(description = "적용 시작 시각. UTC 자정부터 센 분입니다.")
            @Nullable Integer utcStartMinute,
            @Schema(description = "적용 끝 시각(제외). UTC 자정부터 센 분이고 1440은 자정입니다.")
            @Nullable Integer utcEndMinute,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "해석하지 못한 조건의 벤더 이름. 비어 있지 않으면 위 조건만으로는 "
                            + "적용 범위를 다 알 수 없습니다.")
            List<String> otherConditions,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "이 구간에서 바뀌는 가격 축.")
            List<ModelPriceAxis> axes) {
    }
}
