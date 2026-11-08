package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.llm.dto.LlmKeyModelUsageResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyModelUsage}: one model's share of a key's usage
 * over the requested window.
 */
public record InstitutionAdminLlmModelUsageResponse(
        @Schema(description = """
                호출한 모델의 공개 이름이에요. 모델이 정해지기 전에 실패했으면 null이에요.""")
        @Nullable String modelName,
        long requests,
        long succeeded,
        long rateLimited,
        long failed,
        long inputTokens,
        long outputTokens,
        long estimatedRequests,
        @Schema(description = """
                이 모델 요청의 평균 응답 시간이에요(ms). 실패와 거절을 포함하므로 정상 응답만 집계한 백분위와 구분해요.""")
        long avgLatencyMs,
        @Schema(description = """
                선택 기간에 공급자가 이 모델 요청에 보고한 금액의 합이에요(USD). 한도나 잔액과 직접 비교할 값은 아니며 pricedRequests에 해당하는 요청만 포함해요. 가격이 확인된 요청이 없으면 null이에요.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = """
                이 모델 요청 중 예산 축에 관계없이 금액이 보고된 요청 수예요. 포함되지 않은 요청은 금액이 보고되지 않았으며 무료를 뜻하지 않아요. 금융 정보가 미제공이면 null이에요.""")
        @Nullable Long pricedRequests,
        @Schema(description = """
                가격이 확인된 요청의 입력 토큰 수예요. 미확인 요청의 입력 토큰 수는 inputTokens에서 이 값을 빼서 구해요.""")
        @Nullable Long pricedInputTokens,
        @Schema(description = "금액이 붙은 요청이 쓴 출력 토큰 수")
        @Nullable Long pricedOutputTokens,
        @Schema(description = "가격이 확인된 요청의 평균 응답 시간이에요(ms). 해당 요청이 없으면 0이에요.")
        @Nullable Long pricedAvgLatencyMs,
        @Schema(description = """
                가격이 확인되지 않은 요청의 평균 응답 시간이에요(ms). 해당 요청이 없으면 0이에요.""")
        @Nullable Long unpricedAvgLatencyMs,
        @Schema(description = """
                가격이 확인된 요청 중 실패한 수""")
        @Nullable Long pricedFailed,
        @Schema(description = "이 모델이 돌려준 이미지 수")
        long imageCount) {

    public static InstitutionAdminLlmModelUsageResponse of(LlmKeyModelUsageResponse v, boolean finance) {
        return new InstitutionAdminLlmModelUsageResponse(v.modelName(), v.requests(), v.succeeded(), v.rateLimited(), v.failed(),
                v.inputTokens(), v.outputTokens(), v.estimatedRequests(), v.avgLatencyMs(), finance ? v.attributedCostUsd() : null,
                finance ? v.pricedRequests() : null, finance ? v.pricedInputTokens() : null, finance ? v.pricedOutputTokens() : null,
                finance ? v.pricedAvgLatencyMs() : null, finance ? v.unpricedAvgLatencyMs() : null, finance ? v.pricedFailed() : null, v.imageCount());
    }

}
