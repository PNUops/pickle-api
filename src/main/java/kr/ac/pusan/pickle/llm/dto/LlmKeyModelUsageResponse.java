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
        @Schema(description = """
                금액이 붙은 요청이 쓴 입력 토큰 수. 화면이 이 모델을 「금액 확인」과 「금액 \
                미상」 두 행으로 나누어 각 행의 토큰을 보여 주기 위한 값이고, 미상 쪽 토큰은 \
                전체에서 이 값을 빼서 얻습니다. **관리자 화면의 모델별 분해가 같은 필드 \
                묶음을 갖습니다** — 한 모델이 두 화면에서 다르게 읽히지 않게 하려는 것입니다.""")
        long pricedInputTokens,
        @Schema(description = "금액이 붙은 요청이 쓴 출력 토큰 수")
        long pricedOutputTokens,
        @Schema(description = "금액이 붙은 요청만의 평균 응답 시간(ms). 그런 요청이 없으면 0입니다.")
        long pricedAvgLatencyMs,
        @Schema(description = """
                금액이 붙지 않은 요청만의 평균 응답 시간(ms). 한 값을 두 행에 되풀이하면 \
                행마다 다른 요청 수를 갖고도 같은 응답 시간을 말하게 되므로 서버가 나눕니다. \
                그런 요청이 없으면 0입니다.""")
        long unpricedAvgLatencyMs,
        @Schema(description = """
                금액이 붙은 요청 가운데 실패한 수. 게이트웨이가 응답을 정산할 때만 금액을 \
                쓰므로 실제로는 언제나 0이지만, 그 불변식은 이 명세의 보증이 아니라 세어서 \
                보냅니다.""")
        long pricedFailed,
        @Schema(description = "이 모델이 돌려준 이미지 수")
        long imageCount) {
}
