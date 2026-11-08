package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.llm.dto.LlmEndpointKindUsageResponse;

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
public record InstitutionAdminLlmEndpointUsageResponse(
        @Schema(description = """
                Gateway가 보고한 요청 경로예요. 새로운 값도 올 수 있고 null은 경로 정보가 기록되지 않았다는 뜻이에요. 이전 기록도 포함하며 기타 경로로 바꾸지 않아요.""")
        @Nullable String endpoint,
        long requests,
        long succeeded,
        @Schema(description = """
                한도로 거절된 요청 수예요. failed와 겹치지 않으며 succeeded와 rateLimited, failed의 합이 requests예요.""")
        long rateLimited,
        @Schema(description = """
                한도 거절 이외의 사유로 실패한 요청 수예요.""")
        long failed,
        long inputTokens,
        long outputTokens,
        @Schema(description = "이 경로의 금액이에요(USD). 가격이 확인된 요청이 없으면 null이에요.")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "예산 축에 관계없이 금액이 보고된 이 경로의 요청 수. 금융 정보 미제공이면 null")
        @Nullable Long pricedRequests,
        @Schema(description = """
                요청 당시 CREDIT 축으로 기록된 이 경로의 요청 수예요. 금융 정보가 미제공이면 null이에요. 실제 공급자나 부담 주체를 뜻하지 않아요.""")
        @Nullable Long creditAxisRequests,
        @Schema(description = "이 경로가 돌려준 이미지 수")
        long imageCount) {

    public static InstitutionAdminLlmEndpointUsageResponse of(LlmEndpointKindUsageResponse v, boolean finance) {
        return new InstitutionAdminLlmEndpointUsageResponse(v.endpoint(), v.requests(), v.succeeded(), v.rateLimited(), v.failed(),
                v.inputTokens(), v.outputTokens(), finance ? v.attributedCostUsd() : null,
                finance ? v.pricedRequests() : null, finance ? v.creditAxisRequests() : null, v.imageCount());
    }

}
