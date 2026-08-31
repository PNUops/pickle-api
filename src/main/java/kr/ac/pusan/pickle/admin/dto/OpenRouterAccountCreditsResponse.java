package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCredentialError;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCreditsFreshness;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterForecastUnavailableReason;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterUnmanagedSpendUnavailableReason;
import org.jspecify.annotations.Nullable;

/** Cached, secret-free financial observation for one OpenRouter account. */
public record OpenRouterAccountCreditsResponse(
        @Schema(description = "OpenRouter가 보고한 구매 credits 합계. 성공 이력이 없으면 null")
        @Nullable BigDecimal totalCredits,
        @Schema(description = "OpenRouter가 보고한 account 누적 사용액. 성공 이력이 없으면 null")
        @Nullable BigDecimal totalUsage,
        @Schema(description = "totalCredits - totalUsage. 음수도 그대로 반환하며 미관측이면 null")
        @Nullable BigDecimal balance,
        @Schema(description = "마지막 성공 기준 30분 미만 FRESH, 그 이상 STALE, 성공 없음 UNKNOWN")
        OpenRouterCreditsFreshness freshness,
        @Schema(description = "현재 금액 값을 vendor에서 관측한 시각. 미관측이면 null")
        @Nullable Instant observedAt,
        @Schema(description = "마지막 성공 시각. 성공 이력이 없으면 null")
        @Nullable Instant lastSuccessAt,
        @Schema(description = "성공·실패를 포함한 마지막 시도 시각. 시도 이력이 없으면 null")
        @Nullable Instant lastAttemptAt,
        @Schema(description = "마지막 시도가 실패했을 때의 분류. vendor 원문은 반환하지 않음")
        @Nullable OpenRouterCredentialError error,
        @Schema(description = "최근 7일 관측 window의 일평균 사용액. 계산 불가하면 null")
        @Nullable BigDecimal averageDailyUsage,
        @Schema(description = "현재 소비 속도가 계속될 때 잔액 소진 예상 시각. 계산 불가하면 null")
        @Nullable Instant depletionForecastAt,
        @Schema(description = "예상 시각이 없는 이유. 예상 시각이 있으면 null")
        @Nullable OpenRouterForecastUnavailableReason forecastUnavailableReason,
        @Schema(description = "예상 계산에 사용한 첫 관측 시각. 이력 부족이면 null")
        @Nullable Instant forecastWindowStartedAt,
        @Schema(description = "첫 paired baseline 뒤 account 누적 사용 증가분. 계산 불가하면 null")
        @Nullable BigDecimal accountUsageSinceBaseline,
        @Schema(description = "Key reset·삭제를 보존한 Pickle 관리 key 사용 증가분. 없으면 null")
        @Nullable BigDecimal managedUsageSinceBaseline,
        @Schema(description = "Account 증가분에서 관리 key 증가분을 뺀 금액. reset 경계면 null")
        @Nullable BigDecimal unmanagedSpend,
        @Schema(description = "미관리 지출이 없는 이유. 값이 있으면 null")
        @Nullable OpenRouterUnmanagedSpendUnavailableReason unmanagedSpendUnavailableReason,
        @Schema(description = "Paired window의 account credits 관측 시각. 없으면 null")
        @Nullable Instant pairedCreditsObservedAt,
        @Schema(description = "Paired window의 전체 key 대사 관측 시각. 없으면 null")
        @Nullable Instant pairedKeysObservedAt,
        @Schema(description = "마지막 성공한 account key 대사의 freshness")
        OpenRouterCreditsFreshness keysFreshness,
        @Schema(description = "마지막으로 account key 대사가 성공한 시각. 없으면 null")
        @Nullable Instant keysLastSuccessAt,
        @Schema(description = "성공·실패를 포함한 마지막 account key 대사 시도 시각")
        @Nullable Instant keysLastAttemptAt,
        @Schema(description = "마지막 account key 대사가 실패했을 때의 오류 분류")
        @Nullable OpenRouterCredentialError keysError,
        @Schema(description = "미관리 지출 baseline의 paired 관측 시각. 없으면 null")
        @Nullable Instant unmanagedBaselineAt) {
}
