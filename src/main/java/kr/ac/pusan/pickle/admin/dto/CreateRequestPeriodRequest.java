package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code CreateRequestPeriodRequest}: publish a usage period.
 * Without a create op the catalogue would be DB-only state.
 */
public record CreateRequestPeriodRequest(
        @NotBlank(message = "기간 이름을 입력해 주세요.")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$|^[a-z0-9]$",
                message = "기간 이름은 소문자와 숫자, 하이픈 1~40자여야 합니다. 하이픈으로 시작하거나 끝날 수 없습니다.")
        String name,

        @NotBlank(message = "표시명을 입력해 주세요.")
        @Size(max = 100, message = "표시명은 100자 이하여야 합니다.")
        String displayName,

        @Schema(description = "종료일. 비우면 무기한 항목이 되고, 신청자가 그것을 고를 수 있게 됩니다.")
        @Nullable LocalDate endDate,

        @Schema(description = "표시 순서. 비우면 0입니다.")
        @Nullable Integer displayOrder) {
}
