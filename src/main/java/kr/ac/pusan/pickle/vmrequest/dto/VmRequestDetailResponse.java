package kr.ac.pusan.pickle.vmrequest.dto;

import java.time.Instant;
import java.time.LocalDate;
import kr.ac.pusan.pickle.vmrequest.VmRequestStatus;

/**
 * Contract schema {@code VmRequestDetail}. {@code review} is null until the
 * request is decided (SUBMITTED/CANCELED).
 */
public record VmRequestDetailResponse(
        Long id,
        Long groupId,
        String groupName,
        Long orgId,
        String orgName,
        Long requesterId,
        String requesterName,
        Long templateId,
        String purpose,
        String courseOrProject,
        String specReason,
        String extraNote,
        int reqVcpu,
        int reqMemoryMb,
        int reqDiskGb,
        LocalDate reqStartDate,
        LocalDate reqEndDate,
        boolean needSsh,
        boolean needHttp,
        boolean needPublic,
        String desiredSubdomain,
        String rootDomain,
        String customDomain,
        String desiredSlug,
        VmRequestStatus status,
        VmRequestReviewResponse review,
        Instant createdAt,
        Instant updatedAt) {
}
