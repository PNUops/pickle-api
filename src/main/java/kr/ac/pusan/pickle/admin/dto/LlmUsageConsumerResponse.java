package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
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
                이 소비처가 이 기간에 쓴 금액(USD)으로, 공급자가 키마다 보고한 사용액에서 \
                이 기간의 몫만 뽑은 값입니다. 요청마다 더한 `attributedCostUsd`와 달리 요청별 \
                금액이 없던 시기의 지출도 들어 있으므로, 소비처 단위 금액은 이 값을 씁니다. \
                대사가 30분 주기라 방금 쓴 만큼은 아직 안 보이고, 그 시각은 \
                `meteredObservedAt`이 말합니다. 유료 모델을 쓰지 않는 소비처나 아직 한 번도 \
                대사되지 않은 소비처는 null입니다.""")
        @Nullable BigDecimal meteredCostUsd,
        @Schema(description = """
                이 소비처의 금액을 요청마다 더한 값(USD). 모델별 표가 쓰는 것과 같은 출처이고, \
                요청별 금액이 기록되기 전의 지출은 빠져 있습니다. 소비처 표는 \
                `meteredCostUsd`를 쓰므로 이 값은 두 출처를 견줄 때만 씁니다. 가격이 붙은 \
                요청이 하나도 없으면 null이며 0으로 표시하지 않습니다.""")
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
        long creditAxisRequests,
        @Schema(description = """
                이 소비처의 키 가운데 가장 오래된 공급자 대사 시각. 「여기 있는 금액은 적어도 \
                이 시점까지의 것」이라는 뜻이라 가장 최근 시각을 쓰지 않습니다. 금액이 없으면 \
                null입니다.""")
        @Nullable Instant meteredObservedAt) {
}
