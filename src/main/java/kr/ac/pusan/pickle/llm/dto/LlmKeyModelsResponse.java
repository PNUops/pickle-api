package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
@Schema(description = "이 LLM API 키로 호출할 수 있는 모델")
public record LlmKeyModelsResponse(
        @Schema(description = "자체 서빙 모델") List<SelfServedModel> selfServed,
        @Schema(description = "유료 모델") PaidModels paid) {

    /** A model this platform serves. Its upstream is deliberately not exposed. */
    @Schema(description = "자체 서빙 모델")
    public record SelfServedModel(
            @Schema(description = "요청의 model 필드에 넣는 이름", example = "pickle-general")
            String name,
            @Schema(description = "최대 입력 토큰. 상한이 없으면 비어 있습니다")
            @Nullable Integer maxInputTokens,
            @Schema(description = "최대 출력 토큰. 상한이 없으면 비어 있습니다")
            @Nullable Integer maxOutputTokens) {
    }

    /**
     * Whether the money axis is open on this key, and why not when it is shut.
     *
     * <p>The list is sent in every state. A key still waiting for a budget is
     * the one most likely to be looking, because what it needs next is to know
     * what to ask for.
     */
    @Schema(description = "유료 모델 사용 가능 여부. LISTED는 허용 목록과 차단 목록 중 "
            + "어느 쪽으로든 좁혀진 상태이고, UNRESTRICTED는 두 목록이 모두 비어 금액만이 "
            + "경계인 상태입니다.")
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
            @Schema(description = "사용 가능 여부") PaidAccess access,
            @Schema(description = "이 키의 모델 허용 목록. 비어 있으면 허용 쪽 제한이 없습니다.")
            List<String> allowedPatterns,
            @Schema(description = "이 키의 모델 차단 목록. 비어 있으면 차단하는 모델이 없습니다. "
                    + "허용 목록과 함께 걸리면 차단이 이깁니다.")
            List<String> deniedPatterns,
            @Schema(description = "호출할 수 있는 모델") List<PaidModel> models,
            @Schema(description = "허용 목록에 적혀 있지만 지금 목록에서 찾지 못한 이름. "
                    + "오타이거나, 벤더가 내린 모델이거나, 목록이 오래된 것입니다.")
            List<String> unmatchedAllowedPatterns,
            // Deliberately not worded as a warning. A deny rule that matches
            // nothing today is as likely to be pre-emptive as mistyped — the
            // catalogue grows several models a week, and blocking a tier before
            // it ships is half the reason this round added wildcards. Wording it
            // as a problem gets a reviewer to delete a rule they meant, and the
            // consequence of that arrives later, on the day the model appears.
            @Schema(description = "차단 목록에 적혀 있지만 지금 목록에서 찾지 못한 이름. "
                    + "지금은 아무 모델도 막지 않고 있다는 뜻이며, 아직 나오지 않은 모델을 "
                    + "미리 막아 둔 경우에도 여기에 나옵니다.")
            List<String> unmatchedDeniedPatterns,
            @Schema(description = "목록의 신선도") CatalogFreshness catalogFreshness,
            @Schema(description = "목록을 마지막으로 가져온 시각. 한 번도 성공하지 못했으면 비어 있습니다")
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
            @Schema(description = "입력 100만 토큰당 USD. 모르면 비어 있습니다")
            @Nullable BigDecimal promptPricePerMillion,
            @Schema(description = "출력 100만 토큰당 USD. 모르면 비어 있습니다")
            @Nullable BigDecimal completionPricePerMillion,
            @Schema(description = "컨텍스트 길이. 모르면 비어 있습니다")
            @Nullable Integer contextLength) {
    }
}
