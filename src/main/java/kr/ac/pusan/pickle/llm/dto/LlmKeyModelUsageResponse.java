package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyModelUsage}: one model's share of a key's usage
 * over the requested window.
 */
public record LlmKeyModelUsageResponse(
        @Schema(description = """
                호출한 모델의 공개 이름. 모델이 정해지기 전에 실패한 요청은 null이며, \
                화면에서는 '모델 미상'으로 묶입니다.""")
        @Nullable String modelName,
        long requests,
        long succeeded,
        long rateLimited,
        long failed,
        long inputTokens,
        long outputTokens,
        long estimatedRequests,
        @Schema(description = """
                이 모델 요청의 평균 응답 시간(ms). 실패와 거부까지 포함한 평균이라 \
                정상 응답만 재는 백분위와는 다른 값입니다.""")
        long avgLatencyMs,
        @Schema(description = """
                이 기간에 이 모델이 쓴 금액(USD)으로, 요청마다 공급자가 알려 준 값을 더한 \
                것입니다. 한도나 잔액과 견주는 숫자가 아닙니다 — 그쪽은 공급자 미터가 답하고 \
                기간도 다릅니다. 가격이 붙지 않은 요청이 하나라도 있으면 이 합계는 그만큼 \
                작으므로 pricedRequests와 함께 읽습니다. 이 기간에 가격이 붙은 요청이 \
                하나도 없으면 null이며 0으로 표시하지 않습니다.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = """
                이 모델 요청 중 공급자가 금액을 알려 준 요청 수. requests보다 작으면 나머지는 \
                금액을 모르는 것이지 공짜였던 것이 아닙니다.""")
        long pricedRequests,
        @Schema(description = "이 모델이 돌려준 이미지 수")
        long imageCount) {
}
