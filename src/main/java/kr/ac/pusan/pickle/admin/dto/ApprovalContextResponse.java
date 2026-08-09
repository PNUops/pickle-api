package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.request.ReviewDecision;
import kr.ac.pusan.pickle.request.RequestStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code ApprovalContext} — the decision-support panels shown
 * beside a request: applicant summary, current resources of
 * applicant and workspace, request history, org headroom and a Korean guidance
 * line derived from the warning thresholds.
 */
public record ApprovalContextResponse(
        Applicant applicant,
        Resources applicantResources,
        WorkspacePanel workspace,
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

    public record WorkspacePanel(
            Long id,
            String name,
            WorkspaceKind kind,
            List<MemberBrief> members,
            List<VmBriefResponse> activeVms,
            ResourceTotalsResponse totals) {
    }

    public record MemberBrief(Long userId, String name, WorkspaceMemberRole role) {
    }

    public record HistoryEntry(
            Long requestId,
            Instant submittedAt,
            RequestStatus status,
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
