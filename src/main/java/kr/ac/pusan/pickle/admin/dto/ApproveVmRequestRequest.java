package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Contract schema {@code ApproveVmRequest} — the approve form, pre-filled from
 * the requested spec and adjustable by the reviewer. {@code nodeId} null means
 * auto placement.
 */
public record ApproveVmRequestRequest(
        @NotNull(message = "부여 vCPU 수를 입력해 주세요.")
        @Min(value = 1, message = "vCPU는 1 이상이어야 합니다.")
        Integer grantedVcpu,

        @NotNull(message = "부여 메모리를 입력해 주세요.")
        @Min(value = 256, message = "메모리는 256MiB 이상이어야 합니다.")
        Integer grantedMemoryMb,

        @NotNull(message = "부여 디스크 크기를 입력해 주세요.")
        @Min(value = 1, message = "디스크는 1GiB 이상이어야 합니다.")
        Integer grantedDiskGb,

        @NotNull(message = "부여 템플릿(grantedTemplateId)을 지정해 주세요.")
        Long grantedTemplateId,

        LocalDate grantedStartDate,

        LocalDate grantedEndDate,

        @NotNull(message = "SSH 허용 여부를 지정해 주세요.")
        Boolean grantSsh,

        @NotNull(message = "HTTP 게시 허용 여부를 지정해 주세요.")
        Boolean grantHttp,

        @NotNull(message = "외부 공개 허용 여부를 지정해 주세요.")
        Boolean grantPublic,

        // Validated server-side by SubdomainPolicy (RFC 1123 + reserved +
        // profanity + uniqueness); null/blank ⇒ AUTO at publish.
        String grantedSubdomain,

        String grantedRootDomain,

        Long nodeId,

        @Size(max = 2000, message = "승인 의견은 2000자 이하여야 합니다.")
        String comment) {
}
