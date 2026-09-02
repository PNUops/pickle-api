package kr.ac.pusan.pickle.request.vm.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a VM request asks for, nested under {@code vm} in the create body.
 * Cross-field rules (spec reason against the chosen preset, the OS image's
 * minimum disk, slug availability) are validated in the service.
 */
public record CreateVmRequestSpec(
        @NotNull(message = "OS 이미지(imageId)를 지정해 주세요.")
        UUID imageId,

        /**
         * 고른 사양. 프리셋을 쓰지 않고 값을 직접 적었다면 비운다 -- 그때는
         * {@code specReason}이 필수가 된다. 규칙이 카탈로그가 아니라 사용자가 고른
         * 경로를 따르게 하려는 것이다: 가장 큰 프리셋을 대신 실어 보내면, 관리자가
         * 더 큰 프리셋을 하나 추가하는 순간 사유 없이 통과하는 신청이 생긴다.
         */
        @Nullable UUID flavorId,

        @NotNull(message = "요청 vCPU 수를 입력해 주세요.")
        @Min(value = 1, message = "vCPU는 1 이상이어야 합니다.")
        Integer reqVcpu,

        @NotNull(message = "요청 메모리를 입력해 주세요.")
        @Min(value = 256, message = "메모리는 256MiB 이상이어야 합니다.")
        Integer reqMemoryMb,

        @NotNull(message = "요청 디스크 크기를 입력해 주세요.")
        @Min(value = 1, message = "디스크는 1GiB 이상이어야 합니다.")
        Integer reqDiskGb,

        @Size(max = 2000, message = "사양 사유는 2000자 이하여야 합니다.")
        @Nullable String specReason,

        // Blank ≡ null (미지정 → 승인 시 자동 생성) — 예약어·중복(파기 VM 포함)은
        // 서버(VmSlugPolicy + 서비스)에서 검증.
        @Pattern(regexp = "^\\s*$|^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$",
                message = "호스트명(슬러그)은 3~40자의 소문자·숫자·하이픈이어야 합니다 (하이픈으로 시작/끝 불가).")
        @Nullable String desiredSlug) {
}
