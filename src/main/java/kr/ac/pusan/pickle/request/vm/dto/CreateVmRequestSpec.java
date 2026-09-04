package kr.ac.pusan.pickle.request.vm.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a VM request asks for, nested under {@code vm} in the create body.
 * Cross-field rules (the spec reason against whichever baseline the chosen path
 * sets, the OS image's minimum disk, slug availability) are validated in the
 * service.
 */
public record CreateVmRequestSpec(
        @NotNull(message = "OS 이미지(imageId)를 지정해 주세요.")
        UUID imageId,

        /**
         * 고른 사양. 준비된 사양을 쓰지 않고 값을 직접 적었다면 비운다.
         *
         * <p>비었다고 해서 {@code specReason}이 곧바로 필수가 되지는 않는다. 그때의
         * 기준선은 고정 바닥값이고, 그것을 넘을 때만 사유를 요구한다. 바닥값은 준비된
         * 어느 사양보다도 한 축 이상 작으므로, 그대로 낸 신청은 카탈로그가 주는 것보다
         * 적게 달라는 신청이다. 판정은 {@code VmRequestSupport.validateSpec}에 있다.</p>
         *
         * <p>규칙이 카탈로그가 아니라 사용자가 고른 경로를 따르게 하려는 것이다. 가장 큰
         * 사양을 대신 실어 보내면, 관리자가 더 큰 사양을 하나 추가하는 순간 사유 없이
         * 통과하는 신청이 생긴다.</p>
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
                message = "소문자와 숫자, 하이픈만 쓸 수 있고 3~40자여야 합니다. 하이픈으로 시작하거나 끝낼 수 없습니다.")
        @Nullable String desiredSlug) {
}
