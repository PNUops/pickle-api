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
                요청한 모델의 공개 이름이에요. 모델이 정해지기 전에 실패한 요청은 null이며 \
                모델 미상으로 구분해요.""")
        @Nullable String modelName,
        long requests,
        long succeeded,
        long rateLimited,
        long failed,
        long inputTokens,
        long outputTokens,
        long estimatedRequests,
        @Schema(description = """
                이 모델 요청의 평균 응답 시간이에요(ms). 실패와 거절도 포함하므로 \
                정상 응답만 집계한 백분위와는 다른 값이에요.""")
        long avgLatencyMs,
        @Schema(description = """
                이 기간에 이 모델 요청에 공급자가 보고한 금액의 합계예요(USD). 한도나 잔액과 \
                비교하는 값이 아니며 금액이 보고된 요청만 포함해요(pricedRequests 참조). \
                금액이 보고된 요청이 없으면 null이에요.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = """
                이 모델 요청 중 예산 축에 관계없이 금액이 보고된 요청 수예요. \
                포함되지 않은 요청은 금액이 보고되지 않았으며 무료를 뜻하지 않아요.""")
        long pricedRequests,
        @Schema(description = """
                금액이 보고된 요청의 입력 토큰 수예요. 금액이 보고되지 않은 요청의 입력 토큰 수는 \
                inputTokens에서 이 값을 빼서 구해요.""")
        long pricedInputTokens,
        @Schema(description = "금액이 붙은 요청이 쓴 출력 토큰 수")
        long pricedOutputTokens,
        @Schema(description = "금액이 보고된 요청만의 평균 응답 시간이에요(ms). 해당 요청이 없으면 0이에요.")
        long pricedAvgLatencyMs,
        @Schema(description = """
                금액이 보고되지 않은 요청만의 평균 응답 시간이에요(ms). 해당 요청이 없으면 0이에요.""")
        long unpricedAvgLatencyMs,
        @Schema(description = """
                금액이 붙은 요청 가운데 실패한 수.""")
        long pricedFailed,
        @Schema(description = "이 모델이 돌려준 이미지 수")
        long imageCount) {
}
