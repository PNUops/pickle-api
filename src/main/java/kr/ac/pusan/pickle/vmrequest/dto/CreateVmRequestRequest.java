package kr.ac.pusan.pickle.vmrequest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

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

        @NotBlank(message = "사용 목적을 입력해 주세요.")
        @Size(max = 2000, message = "사용 목적은 2000자 이하여야 합니다.")
        String purpose,

        @Size(max = 200, message = "수업/프로젝트명은 200자 이하여야 합니다.")
        String courseOrProject,

        @Size(max = 2000, message = "스펙 사유는 2000자 이하여야 합니다.")
        String specReason,

        @Size(max = 2000, message = "기타 참고 사항은 2000자 이하여야 합니다.")
        String extraNote,

        @NotNull(message = "요청 vCPU 수를 입력해 주세요.")
        @Min(value = 1, message = "vCPU는 1 이상이어야 합니다.")
        Integer reqVcpu,

        @NotNull(message = "요청 메모리를 입력해 주세요.")
        @Min(value = 256, message = "메모리는 256MiB 이상이어야 합니다.")
        Integer reqMemoryMb,

        @NotNull(message = "요청 디스크 크기를 입력해 주세요.")
        @Min(value = 1, message = "디스크는 1GiB 이상이어야 합니다.")
        Integer reqDiskGb,

        LocalDate reqStartDate,

        LocalDate reqEndDate,

        @NotNull(message = "SSH 접속 필요 여부를 지정해 주세요.")
        Boolean needSsh,

        @NotNull(message = "HTTP 게시 필요 여부를 지정해 주세요.")
        Boolean needHttp,

        @NotNull(message = "외부 공개 필요 여부를 지정해 주세요.")
        Boolean needPublic,

        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$",
                message = "서브도메인은 3~40자의 소문자·숫자·하이픈이어야 합니다 (하이픈으로 시작/끝 불가).")
        String desiredSubdomain,

        @Size(max = 253, message = "루트 도메인이 올바르지 않습니다.")
        String rootDomain,

        @Pattern(regexp = "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$",
                message = "커스텀 도메인이 올바른 호스트명 형식이 아닙니다.")
        String customDomain) {
}
