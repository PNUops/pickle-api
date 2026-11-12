package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** One model's share of the selected window, across the selected scope. */
public record LlmUsageModelBreakdownResponse(
        @Schema(description = """
                호출한 모델의 공개 이름이에요. 모델이 정해지기 전에 실패했으면 null이에요.""")
        @Nullable String modelName,
        long requests,
        long failed,
        long inputTokens,
        long outputTokens,
        @Schema(description = "이 모델의 보고 금액이에요(USD). 가격이 확인된 요청이 없으면 null이에요.")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "예산 축에 관계없이 금액이 보고된 이 모델의 요청 수. 금융 정보 미제공이면 null")
        @Nullable Long pricedRequests,
        @Schema(description = """
                요청 당시 CREDIT 축으로 기록된 이 모델의 요청 수예요. 0이면 해당 축의 기록이 없으며 금융 정보가 미제공이면 null이에요. 실제 공급자나 부담 주체를 뜻하지 않아요.""")
        @Nullable Long creditAxisRequests,
        @Schema(description = """
                가격이 확인된 요청의 입력 토큰 수예요. 미확인 요청의 입력 토큰 수는 전체에서 이 값을 빼서 구해요.""")
        @Nullable Long pricedInputTokens,
        @Schema(description = "금액이 붙은 요청이 쓴 출력 토큰 수")
        @Nullable Long pricedOutputTokens,
        @Schema(description = """
                이 모델 전체 요청의 평균 응답 시간이에요(ms). 일별 지연 합을 요청 수로 나눈 값이며 백분위를 뜻하지 않아요.""")
        long avgLatencyMs,
        @Schema(description = "가격이 확인된 요청의 평균 응답 시간이에요(ms). 해당 요청이 없으면 0이에요.")
        @Nullable Long pricedAvgLatencyMs,
        @Schema(description = """
                가격이 확인되지 않은 요청의 평균 응답 시간이에요(ms). 해당 요청이 없으면 0이에요.""")
        @Nullable Long unpricedAvgLatencyMs,
        @Schema(description = """
                가격이 확인된 요청 중 실패로 기록된 수예요. 해당 기록이 없으면 0이에요.""")
        @Nullable Long pricedFailed) {
}
