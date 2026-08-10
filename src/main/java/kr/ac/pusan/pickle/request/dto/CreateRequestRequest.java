package kr.ac.pusan.pickle.request.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.request.vm.dto.CreateVmRequestSpec;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code CreateRequest}. The common fields are the same for
 * every resource type; what is being asked for goes in the nested member named
 * after the type, and the service refuses a body whose {@code type} and nested
 * member disagree.
 *
 * <p>Composed rather than modelled as a discriminated union: a new resource
 * type adds one nullable member here, which keeps the generated schema (and so
 * the console's types) a plain object rather than a set of variants to narrow.
 */
public record CreateRequestRequest(
        @NotNull(message = "신청할 리소스 종류(type)를 지정해 주세요.")
        ResourceType type,

        @NotNull(message = "신청 워크스페이스(workspaceId)를 지정해 주세요.")
        UUID workspaceId,

        @NotNull(message = "기관(orgId)을 지정해 주세요.")
        UUID orgId,

        @NotBlank(message = "사용 목적을 입력해 주세요.")
        @Size(max = 2000, message = "사용 목적은 2000자 이하여야 합니다.")
        String purpose,

        @Size(max = 200, message = "수업/프로젝트명은 200자 이하여야 합니다.")
        @Nullable String courseOrProject,

        @Size(max = 2000, message = "기타 참고 사항은 2000자 이하여야 합니다.")
        @Nullable String extraNote,

        @Nullable LocalDate reqStartDate,

        @Nullable LocalDate reqEndDate,

        // 선택 입력 — 리소스 표시명을 신청 단계에서 지정.
        @Size(max = 100, message = "표시명은 100자 이하여야 합니다.")
        @Nullable String displayName,

        /** Required when {@code type} is VM, ignored otherwise. */
        @Valid @Nullable CreateVmRequestSpec vm) {
}
