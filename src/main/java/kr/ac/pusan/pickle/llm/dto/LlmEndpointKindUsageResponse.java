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
        @Schema(description = """
                한도에 걸려 거부된 요청 수. failed와 겹치지 않습니다 — 셋을 더하면 \
                requests가 됩니다.""")
        long rateLimited,
        @Schema(description = """
                한도 거부가 **아닌** 사유로 실패한 요청 수. 한도 거부를 여기 넣지 않는 것은 \
                집계 표와 같은 가름이라야 같은 이름이 두 숫자를 뜻하지 않기 때문입니다.""")
        long failed,
        long inputTokens,
        long outputTokens,
        @Schema(description = "이 경로가 쓴 금액(USD). 가격이 붙은 요청이 없으면 null입니다.")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "이 경로 요청 중 공급자가 금액을 알려 준 요청 수")
        long pricedRequests,
        @Schema(description = """
                이 경로 요청 중 유료 모델로 나간 요청 수. 한 경로에는 두 축이 섞이므로 \
                금액이 빠진 건수를 세려면 전체 요청이 아니라 이 값에서 빼야 합니다. \
                자체 서빙 요청에는 금액이라는 것이 아예 없으므로, 전체 요청에서 빼면 \
                자체 서빙까지 「금액을 모르는 요청」으로 세게 됩니다.""")
        long creditAxisRequests,
        @Schema(description = "이 경로가 돌려준 이미지 수")
        long imageCount) {
}
