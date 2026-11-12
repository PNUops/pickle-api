package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.llm.dto.LlmKeyUsageTrendResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyUsagePointResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyErrorTypeResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyLatencyResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyHourlyUsageResponse;

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
public record InstitutionAdminLlmTrendResponse(
        LocalDate from,
        LocalDate to,
        @Schema(description = """
                Gateway가 사용량을 마지막으로 보고한 시각이에요. 배치 전송으로 늦게 반영될 수 있고 보고가 없거나 관측 미제공이면 null이에요.""")
        @Nullable Instant reportedUntil,
        @Schema(description = "하루 한 점, 오래된 날부터")
        @Nullable List<LlmKeyUsagePointResponse> points,
        @Schema(description = "이 기간에 실제로 호출된 모델만, 요청이 많은 순")
        @Nullable List<InstitutionAdminLlmModelUsageResponse> models,
        @Schema(description = "이 기간의 실패를 종류별로, 많은 순")
        @Nullable List<LlmKeyErrorTypeResponse> errorTypes,
        @Schema(description = "정상 응답 요청의 응답 시간 백분위예요. 정상 응답이 없거나 관측 미제공이면 null이에요.")
        @Nullable LlmKeyLatencyResponse latency,
        @Schema(description = "요일 x 시각(KST)의 요청 분포예요. 요청이 있는 칸만 제공해요.")
        @Nullable List<LlmKeyHourlyUsageResponse> hourly,
        @Schema(description = "기간이 아니라 현재 시점의 두 예산 축 상태")
        InstitutionAdminLlmBudgetResponse budget) {

    public static InstitutionAdminLlmTrendResponse of(LlmKeyUsageTrendResponse v, boolean observation, boolean finance) {
        return new InstitutionAdminLlmTrendResponse(v.from(), v.to(), observation ? v.reportedUntil() : null, observation ? v.points() : null,
                observation ? v.models().stream().map(item -> InstitutionAdminLlmModelUsageResponse.of(item, finance)).toList() : null,
                observation ? v.errorTypes() : null, observation ? v.latency() : null, observation ? v.hourly() : null,
                InstitutionAdminLlmBudgetResponse.of(v.budget(), observation, finance));
    }

}
