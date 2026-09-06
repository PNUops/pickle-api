package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** One model's share of the selected window, across the selected scope. */
public record LlmUsageModelBreakdownResponse(
        @Schema(description = """
                호출한 모델의 공개 이름. 모델이 정해지기 전에 실패한 요청은 null이며 \
                화면에서는 「모델 미상」으로 묶입니다.""")
        @Nullable String modelName,
        long requests,
        long failed,
        long inputTokens,
        long outputTokens,
        @Schema(description = "이 모델이 쓴 금액(USD). 가격이 붙은 요청이 없으면 null입니다.")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "이 모델 요청 중 공급자가 금액을 알려 준 요청 수")
        long pricedRequests,
        @Schema(description = """
                평균 응답 시간(ms). 일별 집계의 합을 요청 수로 나눈 값이라 백분위는 낼 수 \
                없습니다. 백분위가 필요하면 키 상세로 갑니다.""")
        long avgLatencyMs) {
}
