package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyUsageTrend}: one key's usage per KST calendar
 * day. The series is gapless — a day with no traffic is a zero row, not an
 * absent one.
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
        List<LlmKeyUsagePointResponse> points) {
}
