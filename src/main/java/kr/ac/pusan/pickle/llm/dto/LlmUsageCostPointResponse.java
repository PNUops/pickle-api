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
                이 날 요청에 보고된 금액의 합계예요(USD). 금액이 보고된 요청이 하나도 \
                없으면 null이며 0으로 표시하지 않아요.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "이 날 요청 중 공급자가 금액을 알려 준 요청 수")
        long pricedRequests,
        @Schema(description = "이 날 기록된 전체 요청 수")
        long requests) {
}
