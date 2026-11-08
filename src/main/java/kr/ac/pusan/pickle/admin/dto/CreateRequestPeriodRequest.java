package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
                message = "기간 이름은 소문자와 숫자, 하이픈으로 1~40자를 입력해 주세요. 처음과 끝에는 하이픈을 쓸 수 없어요.")
        String name,

        @NotBlank(message = "표시명을 입력해 주세요.")
        @Size(max = 100, message = "표시명은 100자 이내로 입력해 주세요.")
        String displayName,

        @Schema(description = "종료일이에요. 모든 기간 항목에는 종료일이 있어요.")
        @NotNull(message = "종료일을 정해 주세요.")
        LocalDate endDate,

        @Schema(description = "표시 순서예요. 비우면 0이에요.")
        @Nullable Integer displayOrder) {
}
