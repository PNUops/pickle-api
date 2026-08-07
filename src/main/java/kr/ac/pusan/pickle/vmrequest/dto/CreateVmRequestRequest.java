package kr.ac.pusan.pickle.vmrequest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code CreateVmRequest}. Cross-field rules (spec-reason,
 * subdomain/domain, template minimums) are validated in the service.
 */
public record CreateVmRequestRequest(
        @NotNull(message = "신청 그룹(groupId)을 지정해 주세요.")
        Long groupId,

        @NotNull(message = "기관(orgId)을 지정해 주세요.")
        Long orgId,

        @NotNull(message = "템플릿(templateId)을 지정해 주세요.")
        Long templateId,

        @NotNull(message = "사양 프리셋(flavorId)을 지정해 주세요.")
        Long flavorId,

        @NotBlank(message = "사용 목적을 입력해 주세요.")
        @Size(max = 2000, message = "사용 목적은 2000자 이하여야 합니다.")
        String purpose,

        @Size(max = 200, message = "수업/프로젝트명은 200자 이하여야 합니다.")
        @Nullable String courseOrProject,

        @Size(max = 2000, message = "사양 사유는 2000자 이하여야 합니다.")
        @Nullable String specReason,

        @Size(max = 2000, message = "기타 참고 사항은 2000자 이하여야 합니다.")
        @Nullable String extraNote,

        @NotNull(message = "요청 vCPU 수를 입력해 주세요.")
        @Min(value = 1, message = "vCPU는 1 이상이어야 합니다.")
        Integer reqVcpu,

        @NotNull(message = "요청 메모리를 입력해 주세요.")
        @Min(value = 256, message = "메모리는 256MiB 이상이어야 합니다.")
        Integer reqMemoryMb,

        @NotNull(message = "요청 디스크 크기를 입력해 주세요.")
        @Min(value = 1, message = "디스크는 1GiB 이상이어야 합니다.")
        Integer reqDiskGb,

        @Nullable LocalDate reqStartDate,

        @Nullable LocalDate reqEndDate,

        // 선택 입력 — VM 표시명(vm_settings display_name)을 신청 단계에서 지정.
        @Size(max = 100, message = "표시명은 100자 이하여야 합니다.")
        @Nullable String displayName,

        // Blank ≡ null (미지정 → 승인 시 자동 생성) — 예약어·중복(파기 VM 포함)은
        // 서버(VmSlugPolicy + 서비스)에서 검증.
        @Pattern(regexp = "^\\s*$|^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$",
                message = "호스트명(슬러그)은 3~40자의 소문자·숫자·하이픈이어야 합니다 (하이픈으로 시작/끝 불가).")
        @Nullable String desiredSlug) {
}
