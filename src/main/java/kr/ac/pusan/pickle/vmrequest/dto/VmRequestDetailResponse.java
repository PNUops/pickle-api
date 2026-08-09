package kr.ac.pusan.pickle.vmrequest.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.vmrequest.VmRequestStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmRequestDetail}. {@code review} is null until the
 * request is decided (SUBMITTED/CANCELED).
 */
public record VmRequestDetailResponse(
        Long id,
        Long workspaceId,
        String workspaceName,
        Long orgId,
        String orgName,
        Long requesterId,
        String requesterName,
        Long imageId,
        @Nullable Long flavorId,
        String purpose,
        @Nullable String courseOrProject,
        @Nullable String specReason,
        @Nullable String extraNote,
        int reqVcpu,
        int reqMemoryMb,
        int reqDiskGb,
        @Nullable LocalDate reqStartDate,
        @Nullable LocalDate reqEndDate,
        @Nullable String desiredSubdomain,
        @Nullable String rootDomain,
        @Nullable String displayName,
        @Nullable String desiredSlug,
        VmRequestStatus status,
        @Nullable VmRequestReviewResponse review,
        Instant createdAt,
        Instant updatedAt) {
}
