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
                이 모델의 요청 중 유료 모델로 나간 요청 수. 모델 하나는 자체 서빙이거나 \
                유료거나 하나이므로 이 값은 사실상 0 아니면 requests 이고, **화면은 이것을 \
                「이 모델에 유료 트래픽이 있었나」로만 씁니다.** 금액이 빠진 건수를 여기서 \
                빼지 않습니다 — 이 표는 한 모델을 두 행으로 나누고 그 둘이 모델의 요청을 \
                남김없이 갈라야 해서, 가격 기준(requests - pricedRequests)으로 자릅니다.""")
        long creditAxisRequests,
        @Schema(description = """
                금액이 붙은 요청이 쓴 입력 토큰 수. 화면이 이 모델을 「금액 확인」과 \
                「금액 미상」 두 행으로 나누어 각 행의 토큰을 보여 주기 위한 값이고, \
                미상 쪽 토큰은 전체에서 이 값을 빼서 얻습니다. 알려진 단가로 모르는 \
                쪽을 추정하려면 두 토큰 수가 갈려 있어야 합니다.""")
        long pricedInputTokens,
        @Schema(description = "금액이 붙은 요청이 쓴 출력 토큰 수")
        long pricedOutputTokens,
        @Schema(description = """
                평균 응답 시간(ms). 일별 집계의 합을 요청 수로 나눈 값이라 백분위는 낼 수 \
                없습니다. 백분위가 필요하면 키 상세로 갑니다. 이 모델 전체 기준입니다.""")
        long avgLatencyMs,
        @Schema(description = "금액이 붙은 요청만의 평균 응답 시간(ms). 그런 요청이 없으면 0입니다.")
        long pricedAvgLatencyMs,
        @Schema(description = """
                금액이 붙지 않은 요청만의 평균 응답 시간(ms). 화면이 이 모델을 두 행으로 \
                나눌 때 각 행이 자기 요청의 값을 갖게 하려고 서버가 나눕니다 — 한 값을 \
                두 행에 되풀이하면 행마다 다른 요청 수를 갖고도 같은 응답 시간을 말하게 \
                됩니다. 그런 요청이 없으면 0입니다.""")
        long unpricedAvgLatencyMs,
        @Schema(description = """
                금액이 붙은 요청 가운데 실패한 수. 게이트웨이가 응답을 정산할 때만 금액을 \
                쓰므로 실제로는 언제나 0이지만, 그 불변식은 다른 레포의 동작이라 세어서 \
                보냅니다. 화면이 이 모델을 두 행으로 나눌 때 각 행의 실패율을 가정이 \
                아니라 자료에서 냅니다.""")
        long pricedFailed) {
}
