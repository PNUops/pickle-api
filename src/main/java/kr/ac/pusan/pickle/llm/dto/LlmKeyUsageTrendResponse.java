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
                게이트웨이가 이 Key의 사용량을 마지막으로 보고한 시각. 전송은 배치라 몇 분 \
                늦을 수 있고, 오늘 자 값은 아직 채워지는 중입니다. 보고가 한 번도 없었으면 \
                null입니다.""")
        @Nullable Instant reportedUntil,
        @Schema(description = "하루 한 점, 오래된 날부터")
        List<LlmKeyUsagePointResponse> points,
        @Schema(description = "이 기간에 실제로 호출된 모델만, 요청이 많은 순")
        List<LlmKeyModelUsageResponse> models,
        @Schema(description = "이 기간의 실패를 종류별로, 많은 순")
        List<LlmKeyErrorTypeResponse> errorTypes,
        @Schema(description = "정상 응답 요청의 응답 시간 백분위. 정상 응답이 없으면 null입니다.")
        @Nullable LlmKeyLatencyResponse latency,
        @Schema(description = "요일 x 시각(KST) 요청 분포. 요청이 있는 칸만 담깁니다.")
        List<LlmKeyHourlyUsageResponse> hourly,
        @Schema(description = "기간이 아니라 현재 시점의 두 예산 축 상태")
        LlmKeyBudgetResponse budget) {
}
