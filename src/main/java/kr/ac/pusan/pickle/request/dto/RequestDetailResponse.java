package kr.ac.pusan.pickle.request.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.request.vm.dto.VmRequestSpecResponse;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code RequestDetail}. {@code review} is null until the
 * request is decided (SUBMITTED/CANCELED), and exactly one per-type member is
 * populated: the one named by {@code type}.
 */
public record RequestDetailResponse(
        Long id,
        ResourceType type,
        Long workspaceId,
        String workspaceName,
        Long orgId,
        String orgName,
        Long requesterId,
        String requesterName,
        String purpose,
        @Nullable String courseOrProject,
        @Nullable String extraNote,
        @Nullable LocalDate reqStartDate,
        @Nullable LocalDate reqEndDate,
        @Nullable String displayName,
        RequestStatus status,
        @Nullable RequestReviewResponse review,
        @Nullable VmRequestSpecResponse vm,
        Instant createdAt,
        Instant updatedAt) {
}
