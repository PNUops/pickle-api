package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmPeriodUpdateRequest}: the new usage period.
 *
 * <p>{@code startDate} keeps its current value when omitted. Exactly one of
 * {@code endDate} and {@code clearEndDate} says what the period ends on, and
 * clearing it is how a VM becomes indefinite after it exists. Approval could
 * already grant an indefinite period, so without this a VM's period could only
 * ever move in one direction.</p>
 */
public record VmPeriodUpdateRequest(
        @Schema(description = "새 종료일. 무기한으로 바꾸려면 clearEndDate를 씁니다.")
        @Nullable LocalDate endDate,

        @Schema(description = "true면 종료일을 지워 무기한으로 만듭니다. endDate와 함께 보낼 수 없습니다.")
        @Nullable Boolean clearEndDate,

        @Nullable LocalDate startDate) {
}
