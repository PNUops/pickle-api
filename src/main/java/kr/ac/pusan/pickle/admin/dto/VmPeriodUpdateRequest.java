package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Contract schema {@code VmPeriodUpdateRequest}: {@code endDate} is the new
 * inclusive end date; {@code startDate} keeps its current value when omitted.
 */
public record VmPeriodUpdateRequest(
        @NotNull(message = "종료일은 필수입니다.") LocalDate endDate,
        LocalDate startDate) {
}
