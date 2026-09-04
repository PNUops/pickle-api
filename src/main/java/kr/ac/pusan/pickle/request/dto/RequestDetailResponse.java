package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.llm.dto.LlmKeyRequestSpecResponse;
import kr.ac.pusan.pickle.request.vm.dto.VmRequestSpecResponse;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code RequestDetail}. {@code review} is null until the
 * request is decided (SUBMITTED/CANCELED), and exactly one per-type member is
 * populated: the one named by {@code type}.
 */
public record RequestDetailResponse(
        UUID id,
        ResourceType type,
        @Schema(description = "신청 대상 워크스페이스. 행이 사라진 경우에만 null입니다.")
        @Nullable UUID workspaceId,
        String workspaceName,
        @Schema(description = "신청 대상 기관. 행이 사라진 경우에만 null입니다.")
        @Nullable UUID orgId,
        String orgName,
        @Schema(description = "신청자. 행이 사라진 경우에만 null입니다.")
        @Nullable UUID requesterId,
        String requesterName,
        String purpose,
        @Nullable String extraNote,
        @Schema(description = "신청한 사용 종료일. 값이 없으면 무기한을 요청한 것입니다.")
        @Nullable LocalDate reqEndDate,
        @Schema(description = "종료일을 고른 기간 항목의 이름. 직접 적었으면 null입니다.",
                example = "2026학년도 1학기")
        @Nullable String periodName,
        String displayName,
        RequestStatus status,
        @Nullable RequestReviewResponse review,
        @Nullable VmRequestSpecResponse vm,

        /** Present when {@code type} is LLM_API_KEY, null otherwise. */
        @Nullable LlmKeyRequestSpecResponse llmKey,
        Instant createdAt,
        Instant updatedAt) {
}
