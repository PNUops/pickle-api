package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.gpu.dto.GpuRequestSpecResponse;
import kr.ac.pusan.pickle.publishing.dto.DomainRequestSpecResponse;
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
        @Schema(description = "신청 대상 워크스페이스예요. 행이 사라졌을 때만 null이에요.")
        @Nullable UUID workspaceId,
        String workspaceName,
        @Schema(description = "신청 대상 기관이에요. 행이 사라졌을 때만 null이에요.")
        @Nullable UUID orgId,
        String orgName,
        @Schema(description = "신청자예요. 행이 사라졌을 때만 null이에요.")
        @Nullable UUID requesterId,
        String requesterName,
        String purpose,
        @Nullable String extraNote,
        @Schema(description = "공통 사용 기간을 정하는 신청의 종료일이에요. 해당 신청에서 값이 없으면 무기한을 요청한 뜻이에요.")
        @Nullable LocalDate reqEndDate,
        @Schema(description = "신청할 때 고른 기간 항목의 현재 이름이에요. 직접 입력한 경우에는 null이에요.",
                example = "2026학년도 1학기")
        @Nullable String periodName,
        String displayName,
        RequestStatus status,
        @Nullable RequestReviewResponse review,
        @Nullable VmRequestSpecResponse vm,

        /** Present when {@code type} is LLM_API_KEY, null otherwise. */
        @Nullable LlmKeyRequestSpecResponse llmKey,
        @Nullable GpuRequestSpecResponse gpu,

        /** Present when {@code type} is DOMAIN, null otherwise. */
        @Nullable DomainRequestSpecResponse domain,
        Instant createdAt,
        Instant updatedAt,
        @Schema(description = "리소스를 받을 대상자 목록이에요. 신청자 본인이 받는 일반 신청은 빈 배열이에요.")
        List<RequestRecipientResponse> recipients,
        @Schema(description = "현재 신청에서 허용된 기관 업무예요. 개인 OWNER 및 ACL 권한과 구분하며, 유료 승인 예산은 같은 출처의 결정 업무와 함께 허용될 때만 포함해요.")
        @Nullable List<String> allowedWorkIds) {
}
