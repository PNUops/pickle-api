package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Usage for one organisation, workspace or key at the selected drill level. */
public record LlmUsageConsumerResponse(
        @Nullable UUID orgId,
        @Nullable String orgName,
        @Nullable UUID workspaceId,
        @Nullable String workspaceName,
        @Nullable UUID keyId,
        @Nullable String keyName,
        long requests,
        long inputTokens,
        long outputTokens,
        @Schema(description = """
                이 소비처가 이 기간에 쓴 금액(USD). 가격이 붙은 요청이 하나도 없으면 \
                null이며 0으로 표시하지 않습니다.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "이 소비처의 요청 중 공급자가 금액을 알려 준 요청 수")
        long pricedRequests) {
}
