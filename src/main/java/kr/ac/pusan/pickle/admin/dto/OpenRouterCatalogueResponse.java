package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.llm.dto.ModelPricing;
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
 *
 * <p><b>Freshness alone does not separate them.</b> A refresh that has never
 * succeeded reads UNKNOWN whether it has never run or has failed every hour
 * since the deploy. {@code lastError} and {@code consecutiveFailures} are what
 * tell those apart, which is why they are on the wire rather than in a log.
 */
@Schema(description = "OpenRouter 모델 카탈로그 캐시. 승인 화면의 모델 선택 후보이며, "
        + "이 목록이 비어 있어도 모델 이름을 직접 입력할 수 있어요.")
public record OpenRouterCatalogueResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "캐시에 저장된 모델이에요. 성공적으로 갱신할 때 공급자 목록에서 사라진 모델을 제외해요.")
        List<OpenRouterCatalogueModel> models,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "마지막 성공 기준 신선도. 갱신 주기의 세 배 이상이면 STALE, "
                        + "성공 기록이 없으면 UNKNOWN이에요.")
        OpenRouterCreditsFreshness freshness,
        @Schema(description = "마지막으로 벤더 목록을 성공적으로 가져온 시각.")
        @Nullable Instant lastSuccessAt,
        @Schema(description = "마지막 갱신 시도 시각이며 실패해도 갱신해요.")
        @Nullable Instant lastAttemptAt,
        @Schema(description = "마지막 실패 분류예요. 성공하면 비우며 공급자 응답 본문은 포함하지 않아요.")
        @Nullable String lastError,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "연속 실패 횟수이며 0은 연속 실패가 없다는 뜻이에요.")
        int consecutiveFailures) {

    /** One model as the picker shows it. */
    @Schema(description = "카탈로그의 모델 한 건.")
    public record OpenRouterCatalogueModel(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "허용 목록에 그대로 넣을 수 있는 모델 이름.")
            String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "벤더가 표시하는 이름.")
            String name,
            @Schema(description = "입력 백만 토큰당 가격이에요(USD). 미확인은 null이며 0은 해당 입력 토큰 항목이 무료라는 뜻이에요.")
            @Nullable BigDecimal promptPricePerMillion,
            @Schema(description = "출력 백만 토큰당 가격이에요(USD). 미확인은 null이며 0은 해당 출력 토큰 항목이 무료라는 뜻이에요.")
            @Nullable BigDecimal completionPricePerMillion,
            @Schema(description = "컨텍스트 길이(토큰).")
            @Nullable Integer contextLength,
            @Schema(description = "입력과 출력을 포함한 전체 가격 항목이에요. 목록에 해당 정보가 없으면 null이에요.")
            @Nullable ModelPricing pricing) {
    }
}
