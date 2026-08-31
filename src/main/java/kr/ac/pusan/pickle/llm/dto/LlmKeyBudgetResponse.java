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
                하루에 쓸 수 있는 토큰 수. null이면 한도가 없고, 0이면 자체 서빙 모델을 \
                쓸 수 없습니다.""")
        @Nullable Long dailyTokens,
        @Schema(description = """
                오늘(KST) 자체 서빙 모델에 쓴 입출력 토큰 합계. 상용 모델 사용은 금액 축에 \
                계상되므로 여기 들어가지 않습니다. 사용량 전송이 배치라 방금 쓴 만큼은 \
                아직 반영되지 않았을 수 있습니다.""")
        long todayTokens,
        @Schema(description = "한도에 도달해 자체 서빙 모델 요청이 거절되고 있는 상태.")
        boolean quotaExhausted,
        @Schema(description = "상용 모델에 쓸 수 있는 금액 한도(USD). 0이면 상용 모델을 쓸 수 없습니다.")
        BigDecimal creditLimit,
        @Schema(description = """
                OpenRouter가 보고한 현재 limit window 사용액(USD). DAILY/WEEKLY/MONTHLY \
                reset 뒤에는 감소할 수 있습니다. 아직 보고된 적이 없으면 null이며 0으로 \
                표시하지 않습니다.""")
        @Nullable BigDecimal creditUsage,
        @Schema(description = "그 limit window 사용액을 읽어 온 시각. 30분마다 갱신됩니다.")
        @Nullable Instant creditUsageAt,
        @Schema(description = """
                최근 소비 속도로 금액 한도에 도달할 것으로 보이는 날짜. 이력이 부족하거나 \
                최근에 쓴 적이 없으면 null입니다 — 그때는 화면이 예상 대신 이유를 말합니다.""")
        @Nullable LocalDate creditDepletionForecast) {
}
