package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmEndpointKindUsage}: what came in on one route.
 *
 * <p>The vocabulary is the gateway's and it is open, so this carries the value
 * as written rather than an enum. A reader that meets a name it does not know
 * shows the name; a null is not "other" but "recorded before the field
 * existed", and the two must not be folded together.
 *
 * <p>The route is not the capability that granted it. One grant covers both
 * image generation and the image catalogue lookup, and only one of those costs
 * money, so counting them in the same bucket would show a free lookup as a
 * paid generation.
 */
public record LlmEndpointKindUsageResponse(
        @Schema(description = """
                요청이 들어온 경로. 값은 게이트웨이가 정하는 열린 어휘이고, null은 \
                「기타」가 아니라 이 축이 기록되기 전의 요청입니다.""")
        @Nullable String endpoint,
        long requests,
        long succeeded,
        long failed,
        long inputTokens,
        long outputTokens,
        @Schema(description = "이 경로가 쓴 금액(USD). 가격이 붙은 요청이 없으면 null입니다.")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "이 경로 요청 중 공급자가 금액을 알려 준 요청 수")
        long pricedRequests,
        @Schema(description = "이 경로가 돌려준 이미지 수")
        long imageCount) {
}
