package kr.ac.pusan.pickle.llm.dto;

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
public record LlmKeyBudgetResponse(
        @Schema(description = """
                하루에 쓸 수 있는 토큰 수예요. null이면 한도가 없고 0이면 자체 서빙 모델을 \
                쓸 수 없어요.""")
        @Nullable Long dailyTokens,
        @Schema(description = """
                오늘(KST) 자체 서빙 모델에 기록된 입출력 토큰 합계예요. 유료 모델 사용량은 금액 \
                한도로 계산하므로 포함하지 않아요. 사용량은 배치로 전달돼 최근 사용량이 \
                아직 반영되지 않았을 수 있어요.""")
        long todayTokens,
        @Schema(description = "자체 서빙 모델의 일일 토큰 한도 소진 상태")
        boolean quotaExhausted,
        @Schema(description = "유료 모델의 금액 한도예요(USD). 0이면 유료 모델을 쓸 수 없어요.")
        BigDecimal creditLimit,
        @Schema(description = """
                공급자가 보고한 키의 누적 사용액이에요(USD). 일간과 주간, 월간 리셋별 사용액이나 \
                현재 창의 소비량을 뜻하지 않아요. 아직 보고된 적이 없으면 null이에요.""")
        @Nullable BigDecimal creditUsage,
        @Schema(description = "누적 사용액을 읽어 온 시각이에요. 30분 주기로 갱신을 시도해요.")
        @Nullable Instant creditUsageAt,
        @Schema(description = """
                총액 한도를 최근 소비 속도로 소진할 것으로 예상하는 날짜예요. 주기별 한도이거나 \
                이력이 부족하거나 최근 소비가 없으면 null이에요.""")
        @Nullable LocalDate creditDepletionForecast) {
}
