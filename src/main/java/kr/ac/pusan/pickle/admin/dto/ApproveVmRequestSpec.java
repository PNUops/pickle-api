package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code ApproveVmRequestSpec}: what the reviewer grants for a
 * VM, nested under {@code vm} in the approve body. Pre-filled from the
 * requested spec and adjustable.
 */
public record ApproveVmRequestSpec(
        @NotNull(message = "부여 vCPU 수를 입력해 주세요.")
        @Min(value = 1, message = "vCPU는 1 이상이어야 합니다.")
        Integer grantedVcpu,

        @NotNull(message = "부여 메모리를 입력해 주세요.")
        @Min(value = 256, message = "메모리는 256MiB 이상이어야 합니다.")
        Integer grantedMemoryMb,

        @NotNull(message = "부여 디스크 크기를 입력해 주세요.")
        @Min(value = 1, message = "디스크는 1GiB 이상이어야 합니다.")
        Integer grantedDiskGb,

        @NotNull(message = "부여 OS 이미지(grantedImageId)를 지정해 주세요.")
        Long grantedImageId,

        // 최종 호스트명(슬러그, v0.12.0) — null/공백이면 기존처럼 자동 생성.
        // Blank ≡ null이므로 패턴이 공백을 허용하고, 예약어·중복(파기 VM 포함)은
        // 서버(VmSlugPolicy + VmRequestSupport)에서 검증한다.
        @Pattern(regexp = "^\\s*$|^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$",
                message = "호스트명(슬러그)은 3~40자의 소문자·숫자·하이픈이어야 합니다 (하이픈으로 시작/끝 불가).")
        @Nullable String grantedSlug,

        /** null이면 자동 배치. */
        @Nullable Long nodeId) {
}
