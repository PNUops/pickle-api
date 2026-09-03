package kr.ac.pusan.pickle.request.dto;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.llm.dto.CreateLlmKeyRequestSpec;
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

        @Size(max = 2000, message = "기타 참고 사항은 2000자 이하여야 합니다.")
        @Nullable String extraNote,

        /**
         * 고른 기간 항목({@code GET /request-periods}). 직접 날짜를 적었으면 비운다.
         * 값이 있으면 종료일은 서버가 그 항목에서 복사하므로 {@code reqEndDate}와 함께
         * 보낼 수 없다.
         */
        @Nullable UUID periodPresetId,

        /**
         * 직접 적은 종료일. 기간 항목도 무기한도 아니라면 필수다.
         */
        @Nullable LocalDate reqEndDate,

        /**
         * 끝나지 않는 사용 기간을 요청한다.
         *
         * <p>**비어 있는 종료일이 곧 무기한인 것이 아니라, 이 값이 무기한이다.** 빠뜨린
         * 종료일과 일부러 비운 종료일은 본문에서 똑같이 생겼으므로, 둘을 나눌 값이
         * 따로 없으면 실수로 낸 신청이 만료되지 않는 VM이 된다.</p>
         */
        @Schema(description = "true면 종료일 없이 신청합니다. reqEndDate·periodPresetId와 함께 보낼 수 없습니다.")
        @Nullable Boolean reqIndefinite,

        // 신청하는 리소스의 이름. 종류를 가리지 않고 필수이며, 이 신청을 가리키는
        // 응답은 어디서나 식별자 옆에 이 이름을 함께 싣는다.
        @NotBlank(message = "리소스 이름을 입력해 주세요.")
        @Size(max = 100, message = "리소스 이름은 100자 이하여야 합니다.")
        String displayName,

        /** Required when {@code type} is VM, ignored otherwise. */
        @Valid @Nullable CreateVmRequestSpec vm,

        /** Required when {@code type} is LLM_API_KEY, ignored otherwise. */
        @Valid @Nullable CreateLlmKeyRequestSpec llmKey) {
}
