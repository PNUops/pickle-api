package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyUsageTrend}: one key's usage per KST calendar
 * day, plus the breakdowns and the budget standing shown beside it.
 *
 * <p>The daily series is gapless — a day with no traffic is a zero row, not an
 * absent one. The breakdowns are the opposite: they carry only what happened,
 * because a list of every model that was not called says nothing.
 *
 * <p>All of it answers the same window ({@code from}..{@code to}) except
 * {@code budget}, which is about right now: a budget is a present state, not a
 * historical one, and showing a 30-day-old allowance as if it were current
 * would be the more misleading answer.
 */
public record LlmKeyUsageTrendResponse(
        LocalDate from,
        LocalDate to,
        @Schema(description = """
                Gateway가 이 키의 사용량을 마지막으로 보고한 시각이에요. 배치로 보고해 \
                지연될 수 있어요. 보고 기록이 없으면 null이에요.""")
        @Nullable Instant reportedUntil,
        @Schema(description = "하루 한 점, 오래된 날부터")
        List<LlmKeyUsagePointResponse> points,
        @Schema(description = "이 기간에 요청이 기록된 모델이며, 요청이 많은 순서")
        List<LlmKeyModelUsageResponse> models,
        @Schema(description = "이 기간의 실패를 종류별로, 많은 순")
        List<LlmKeyErrorTypeResponse> errorTypes,
        @Schema(description = "정상 응답 요청의 응답 시간 백분위이며, 정상 응답이 없으면 null이에요.")
        @Nullable LlmKeyLatencyResponse latency,
        @Schema(description = "요일과 시각(KST)에 따른 요청 분포이며, 요청이 있는 칸만 제공해요.")
        List<LlmKeyHourlyUsageResponse> hourly,
        @Schema(description = "기간이 아니라 현재 시점의 두 예산 축 상태")
        LlmKeyBudgetResponse budget) {
}
