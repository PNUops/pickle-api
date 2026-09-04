package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCreditsFreshness;
import org.jspecify.annotations.Nullable;

/**
 * The cached vendor model catalogue an approver picks from.
 *
 * <p>Freshness is part of the answer rather than a detail, because an empty or
 * short list has two very different causes: nobody has refreshed yet, or the
 * vendor is unreachable. A screen that cannot tell them apart shows the same
 * blank for both, and the approver types a name without knowing whether the
 * list they are ignoring is broken or simply new.
 */
@Schema(description = "OpenRouter 모델 카탈로그 캐시. 승인 화면의 모델 선택 후보이며, "
        + "이 목록이 비어 있어도 모델 이름을 직접 입력할 수 있습니다.")
public record OpenRouterCatalogueResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "캐시에 남아 있는 모델. 벤더 목록에서 사라진 모델은 제외됩니다.")
        List<CatalogueModel> models,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "마지막 성공 기준 신선도. 갱신 주기의 세 배를 넘기면 STALE, "
                        + "성공한 적이 없으면 UNKNOWN입니다.")
        OpenRouterCreditsFreshness freshness,
        @Schema(description = "마지막으로 벤더 목록을 성공적으로 가져온 시각.")
        @Nullable Instant lastSuccessAt,
        @Schema(description = "마지막 갱신 시도 시각. 실패해도 갱신됩니다.")
        @Nullable Instant lastAttemptAt,
        @Schema(description = "마지막 실패의 분류. 성공했다면 비어 있습니다. 벤더 응답 본문은 담지 않습니다.")
        @Nullable String lastError,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "연속 실패 횟수. 0이면 마지막 시도가 성공했습니다.")
        int consecutiveFailures) {

    /** One model as the picker shows it. */
    @Schema(description = "카탈로그의 모델 한 건.")
    public record CatalogueModel(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "허용 목록에 그대로 넣을 수 있는 모델 이름.")
            String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "벤더가 표시하는 이름.")
            String name,
            @Schema(description = "백만 토큰당 입력 가격(USD). 모르면 비어 있고, 0은 무료를 뜻합니다.")
            @Nullable BigDecimal promptPricePerMillion,
            @Schema(description = "백만 토큰당 출력 가격(USD). 모르면 비어 있고, 0은 무료를 뜻합니다.")
            @Nullable BigDecimal completionPricePerMillion,
            @Schema(description = "컨텍스트 길이(토큰).")
            @Nullable Integer contextLength) {
    }
}
