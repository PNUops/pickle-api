package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import org.jspecify.annotations.Nullable;

/**
 * What one LLM API key may call.
 *
 * <p>Two sections in one response because the screen is one dialog and the two
 * halves come from different places: self-served models are catalogue rows this
 * platform owns, paid models are a cached copy of the vendor's public listing.
 * Splitting them into two calls would make the console combine two failures to
 * decide what to show — an empty paid list has to read as "nothing to list",
 * not as "the dialog is broken".
 *
 * <p><b>This is a list of candidates, not a grant.</b> The gateway decides at
 * call time. Per-key facts are applied here (no money budget, a model outside
 * the key's allow list), so what remains is narrowed by everything this
 * response is about. Two platform-wide conditions are left out on purpose, and
 * the service that builds this says which and why.
 */
@Schema(description = "이 LLM API 키의 모델 후보")
public record LlmKeyModelsResponse(
        @Schema(description = "자체 서빙 모델") List<SelfServedModel> selfServed,
        @Schema(description = "유료 모델") PaidModels paid) {

    /** A model this platform serves. Its upstream is deliberately not exposed. */
    @Schema(description = "자체 서빙 모델")
    public record SelfServedModel(
            @Schema(description = "요청의 model 필드에 넣는 이름", example = "pickle-general")
            String name,
            @Schema(description = "최대 입력 토큰 수이며, 상한이 없으면 null이에요.")
            @Nullable Integer maxInputTokens,
            @Schema(description = "최대 출력 토큰 수이며, 상한이 없으면 null이에요.")
            @Nullable Integer maxOutputTokens) {
    }

    /**
     * Whether the money axis is open on this key, and why not when it is shut.
     *
     * <p>The list is sent in every state. A key still waiting for a budget is
     * the one most likely to be looking, because what it needs next is to know
     * what to ask for.
     */
    @Schema(description = "유료 모델의 연결과 목록 제한 상태예요. LISTED는 허용 목록이나 차단 목록으로 "
            + "범위를 좁힌 상태이며, UNRESTRICTED는 두 목록이 모두 비어 "
            + "목록으로 제한하지 않는 상태예요.")
    public enum PaidAccess {
        /** No money budget yet. The list is what the holder would be asking for. */
        NONE,
        /** Budget granted, its upstream credential still being created. */
        PENDING,
        /** Neither list is set, so the whole cached listing is in reach. */
        UNRESTRICTED,
        /**
         * A list narrows it to the models below — either one. A deny list alone
         * is still a narrowing, and calling that state unrestricted would put a
         * label above the listing that its own contents contradict.
         */
        LISTED
    }

    @Schema(description = "유료 모델과 그 목록의 상태")
    public record PaidModels(
            @Schema(description = "유료 모델 연결과 목록 제한 상태") PaidAccess access,
            @Schema(description = "이 키의 모델 허용 목록이며, 비어 있으면 이 목록으로 제한하지 않아요."
                    + CreditModelPatterns.ALLOW_PATTERN_DESCRIPTION)
            List<String> allowedPatterns,
            @Schema(description = "이 키의 모델 차단 목록이며, 비어 있으면 이 목록으로 차단하지 않아요. "
                    + "허용 목록과 겹치면 차단을 적용해요."
                    + CreditModelPatterns.DENY_PATTERN_DESCRIPTION)
            List<String> deniedPatterns,
            @Schema(description = "정책에 맞는 모델 후보") List<PaidModel> models,
            @Schema(description = "허용 목록에 있지만 현재 모델 목록에서 찾지 못한 이름이에요. "
                    + "목록에서 사라진 모델과 아직 없는 이름, 제한된 키에서 호출할 수 없는 라우터도 포함해요.")
            List<String> unmatchedAllowedPatterns,
            // Deliberately not worded as a warning. A deny rule that matches
            // nothing today is as likely to be pre-emptive as mistyped — the
            // catalogue grows several models a week, and blocking a tier before
            // it ships is half the reason this round added wildcards. Wording it
            // as a problem gets a reviewer to delete a rule they meant, and the
            // consequence of that arrives later, on the day the model appears.
            @Schema(description = "차단 목록에 있지만 현재 모델 목록에서 찾지 못한 이름이에요. "
                    + "현재 캐시의 모델명과 별칭 중 이 패턴으로 차단하는 것이 없다는 뜻이며, 아직 나오지 않은 모델을 "
                    + "미리 차단한 경우에도 여기에 나와요.")
            List<String> unmatchedDeniedPatterns,
            @Schema(description = "목록의 신선도") CatalogFreshness catalogFreshness,
            @Schema(description = "모델 목록을 마지막으로 가져온 시각이며, 성공 기록이 없으면 null이에요.")
            @Nullable Instant catalogObservedAt) {
    }

    /** How much the listing can be trusted. Never carries why a fetch failed. */
    @Schema(description = "목록 신선도")
    public enum CatalogFreshness {
        /** Fetched recently enough to act on. */
        FRESH,
        /** Older than the refresh period allows. Still shown, with a caveat. */
        STALE,
        /** Never fetched successfully. Nothing to show. */
        UNKNOWN
    }

    @Schema(description = "유료 모델")
    public record PaidModel(
            @Schema(description = "요청의 model 필드에 넣는 이름", example = "openai/gpt-5.6-luna")
            String id,
            @Schema(description = "표시 이름") String name,
            @Schema(description = "입력 100만 토큰당 USD이며, 가격을 모르면 null이에요.")
            @Nullable BigDecimal promptPricePerMillion,
            @Schema(description = "출력 100만 토큰당 USD이며, 가격을 모르면 null이에요.")
            @Nullable BigDecimal completionPricePerMillion,
            @Schema(description = "컨텍스트 길이이며, 알 수 없으면 null이에요.")
            @Nullable Integer contextLength,
            @Schema(description = "입력과 출력을 포함한 전체 가격 항목이며, 목록에 가격 정보가 없으면 null이에요.")
            @Nullable ModelPricing pricing) {
    }
}
