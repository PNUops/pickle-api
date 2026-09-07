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
                이 기간에 이 모델 요청에 공급자가 알려 준 금액(USD)의 합. 한도나 잔액과 \
                견주는 값이 아니며, 가격이 붙지 않은 요청은 들어 있지 않습니다 \
                (pricedRequests 참조). 가격이 붙은 요청이 하나도 없으면 null입니다.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = """
                이 모델 요청 중 공급자가 금액을 알려 준 요청 수. requests보다 작으면 나머지는 \
                금액을 모르는 것이지 공짜였던 것이 아닙니다.""")
        long pricedRequests,
        @Schema(description = """
                금액이 붙은 요청이 쓴 입력 토큰 수. 금액이 붙지 않은 요청의 입력 토큰은 \
                inputTokens에서 이 값을 뺀 것입니다.""")
        long pricedInputTokens,
        @Schema(description = "금액이 붙은 요청이 쓴 출력 토큰 수")
        long pricedOutputTokens,
        @Schema(description = "금액이 붙은 요청만의 평균 응답 시간(ms). 그런 요청이 없으면 0입니다.")
        long pricedAvgLatencyMs,
        @Schema(description = """
                금액이 붙지 않은 요청만의 평균 응답 시간(ms). 그런 요청이 없으면 0입니다.""")
        long unpricedAvgLatencyMs,
        @Schema(description = """
                금액이 붙은 요청 가운데 실패한 수.""")
        long pricedFailed,
        @Schema(description = "이 모델이 돌려준 이미지 수")
        long imageCount) {
}
