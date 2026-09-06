package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmUsageCostPoint}: what one KST calendar day cost,
 * from the per-request figures the vendor reported.
 *
 * <p>This series exists apart from the daily usage point rather than as two
 * more fields on it, and the separation is the boundary itself: the key's owner
 * is shown cost per model and never per day, so a schema that carries no daily
 * cost cannot be rendered into one by accident.
 *
 * <p>The amount is attribution, never standing. It says where a total that is
 * already known went; it does not say what is left, and it must not be
 * subtracted from a balance. The windows do not line up and this side is
 * missing whatever the vendor did not price.
 */
public record LlmUsageCostPointResponse(
        LocalDate day,
        @Schema(description = """
                이 날 요청들에 공급자가 매긴 금액의 합(USD). 가격이 붙은 요청이 하나도 \
                없는 날은 null이며 0으로 그리지 않습니다 — 자체 서빙만 쓴 날은 금액이 \
                0인 것이 아니라 금액이라는 것이 없습니다.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "이 날 요청 중 공급자가 금액을 알려 준 요청 수")
        long pricedRequests,
        @Schema(description = "이 날 요청 수. pricedRequests와 견주면 합계가 얼마나 덮는지 보입니다.")
        long requests) {
}
