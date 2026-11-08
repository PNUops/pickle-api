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
                공급자가 키마다 보고한 사용액에서 선택 기간의 차이를 계산한 금액이에요(USD). 요청별 attributedCostUsd가 기록되기 전의 사용도 포함해요. 대사는 기본 30분 주기를 따르며 최근 사용량이 늦게 반영될 수 있어요. meteredObservedAt을 함께 확인해요. 계산에 필요한 대사 관측이 없거나 금융 정보가 미제공이면 null이에요.""")
        @Nullable BigDecimal meteredCostUsd,
        @Schema(description = """
                선택 기간의 요청별 보고 금액을 합한 값이에요(USD). 금액이 기록되기 전의 사용은 포함하지 않아요. meteredCostUsd와는 다른 출처이며 가격이 확인된 요청이 없으면 null이에요. 0으로 판단하지 않아요.""")
        @Nullable BigDecimal attributedCostUsd,
        @Schema(description = "예산 축에 관계없이 금액이 보고된 이 소비처의 요청 수. 금융 정보 미제공이면 null")
        @Nullable Long pricedRequests,
        @Schema(description = """
                요청 당시 CREDIT 축으로 기록된 이 소비처의 요청 수예요. 금융 정보가 미제공이면 null이에요. 실제 공급자나 부담 주체를 뜻하지 않아요.""")
        @Nullable Long creditAxisRequests,
        @Schema(description = """
                이 소비처의 키 중 가장 오래된 공급자 대사 시각이에요. 현재 금액에 반영된 관측의 오래된 경계를 나타내며 금액이 없으면 null이에요.""")
        @Nullable Instant meteredObservedAt) {
}
