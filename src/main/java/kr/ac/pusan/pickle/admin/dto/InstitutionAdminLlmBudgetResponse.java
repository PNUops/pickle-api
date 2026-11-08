package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.llm.dto.LlmKeyBudgetResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyBudget}: where this key stands against each of
 * its two budgets right now.
 *
 * <p>The two axes are measured differently and the response says so rather than
 * hiding it. The token axis is ours: counted from the events we hold, reset at
 * KST midnight, and covering self-serve models only. The money axis is
 * OpenRouter's: they enforce it, we read their cumulative figure periodically,
 * and it carries the time it was read because it is always that stale.
 */
public record InstitutionAdminLlmBudgetResponse(
        @Schema(description = """
                하루에 쓸 수 있는 토큰 수예요. 관측 정보가 제공될 때 null은 무제한이며 0은 자체 서빙 모델 사용 차단을 뜻해요. 관측 정보가 미제공인 경우에도 null이에요.""")
        @Nullable Long dailyTokens,
        @Schema(description = """
                오늘(KST) 자체 서빙 모델에 기록된 입출력 토큰 합계예요. 유료 모델 사용은 금액 한도로 계산하며 포함하지 않아요. 배치 전송으로 최근 사용량이 늦게 반영될 수 있고 관측 미제공이면 null이에요.""")
        @Nullable Long todayTokens,
        @Schema(description = "기록된 자체 서빙 모델의 일일 토큰 한도 소진 상태. 관측 미제공이면 null")
        @Nullable Boolean quotaExhausted,
        @Schema(description = "유료 모델의 금액 한도예요(USD). 0이면 유료 모델을 쓸 수 없고 금융 정보가 미제공이면 null이에요.")
        @Nullable BigDecimal creditLimit,
        @Schema(description = """
                공급자가 보고한 키의 누적 사용액이에요(USD). 일간과 주간, 월간 리셋별 사용액이나 현재 창의 소비량과 구분해요. 미관측이거나 금융 미제공이면 null이에요.""")
        @Nullable BigDecimal creditUsage,
        @Schema(description = "누적 사용액을 읽은 시각이에요. 30분 주기를 기준으로 갱신을 시도하며 미관측이거나 금융 미제공이면 null이에요.")
        @Nullable Instant creditUsageAt,
        @Schema(description = """
                총액 한도를 최근 소비 속도로 소진할 것으로 예상한 날짜예요. 주기별 한도이거나 이력이 부족한 경우, 최근 소비가 없거나 금융 미제공이면 null이에요.""")
        @Nullable LocalDate creditDepletionForecast) {

    public static InstitutionAdminLlmBudgetResponse of(LlmKeyBudgetResponse value, boolean observation, boolean finance) {
        return new InstitutionAdminLlmBudgetResponse(observation ? value.dailyTokens() : null, observation ? value.todayTokens() : null, observation ? value.quotaExhausted() : null,
                finance ? value.creditLimit() : null, finance ? value.creditUsage() : null,
                finance ? value.creditUsageAt() : null, finance ? value.creditDepletionForecast() : null);
    }

}
