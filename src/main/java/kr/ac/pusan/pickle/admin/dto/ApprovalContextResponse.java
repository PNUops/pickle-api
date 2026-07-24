package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.group.GroupKind;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.vmrequest.ReviewDecision;
import kr.ac.pusan.pickle.vmrequest.VmRequestStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code ApprovalContext} — the decision-support panels shown
 * beside a request: applicant summary, current resources of
 * applicant and group, request history, org headroom and a Korean guidance
 * line derived from the warning thresholds.
 */
public record ApprovalContextResponse(
        Applicant applicant,
        Resources applicantResources,
        GroupPanel group,
        List<HistoryEntry> history,
        OrgHeadroom orgHeadroom,
        String guidance) {

    public record Applicant(
            Long id,
            String name,
            String email,
            Instant signupAt,
            long approvedCount,
            long rejectedCount) {
    }

    public record Resources(List<VmBriefResponse> activeVms, ResourceTotalsResponse totals) {
    }

    public record GroupPanel(
            Long id,
            String name,
            GroupKind kind,
            List<MemberBrief> members,
            List<VmBriefResponse> activeVms,
            ResourceTotalsResponse totals) {
    }

    public record MemberBrief(Long userId, String name, GroupMemberRole role) {
    }

    public record HistoryEntry(
            Long requestId,
            Instant submittedAt,
            VmRequestStatus status,
            @Nullable ReviewDecision decision,
            @Nullable String comment,
            @Nullable String reviewerName) {
    }

    public record OrgHeadroom(
            ResourceTotalsResponse allocated,
            Capacity capacity,
            double vcpuOvercommitRatio,
            double memoryUsageRatio,
            List<String> warnings) {
    }

    public record Capacity(long cpuThreads, long memoryMb) {
    }
}
