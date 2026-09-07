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
        long pricedRequests,
        @Schema(description = """
                이 소비처의 요청 중 유료 모델로 나간 요청 수. **한 행이 여러 모델을 \
                묶는 표에서** 금액이 빠진 건수를 세려면 전체 요청이 아니라 이 값에서 \
                빼야 합니다. 자체 서빙 요청에는 금액이라는 것이 아예 없으므로, 전체 \
                요청에서 빼면 자체 서빙까지 「금액을 모르는 요청」으로 세게 됩니다. \
                모델별 표는 다릅니다 — 거기서는 한 모델을 두 행으로 나누고 그 둘이 \
                모델의 요청을 남김없이 갈라야 하므로 가격 기준으로 자릅니다.""")
        long creditAxisRequests) {
}
