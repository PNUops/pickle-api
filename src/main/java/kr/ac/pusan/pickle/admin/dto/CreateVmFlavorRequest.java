package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code CreateVmFlavorRequest} (v0.23.0) — without a create
 * op, adding a preset would be DB-only state (no operational state without a
 * write path).
 */
public record CreateVmFlavorRequest(
        @NotBlank(message = "프리셋 이름을 입력해 주세요.")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$|^[a-z0-9]$",
                message = "프리셋 이름은 소문자·숫자·하이픈 1~40자여야 합니다 (하이픈으로 시작/끝 불가).")
        String name,

        @NotBlank(message = "표시명을 입력해 주세요.")
        @Size(max = 100, message = "표시명은 100자 이하여야 합니다.")
        String displayName,

        @NotNull(message = "vCPU 수를 입력해 주세요.")
        @Min(value = 1, message = "vCPU는 1 이상이어야 합니다.")
        Integer vcpu,

        @NotNull(message = "메모리를 입력해 주세요.")
        @Min(value = 256, message = "메모리는 256MiB 이상이어야 합니다.")
        Integer memoryMb,

        @NotNull(message = "디스크 크기를 입력해 주세요.")
        @Min(value = 1, message = "디스크는 1GiB 이상이어야 합니다.")
        Integer diskGb,

        @Size(max = 2000, message = "비고는 2000자 이하여야 합니다.")
        @Nullable String notes,

        @Nullable Integer displayOrder) {
}
