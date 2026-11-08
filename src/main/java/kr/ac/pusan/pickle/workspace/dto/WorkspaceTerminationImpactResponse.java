package kr.ac.pusan.pickle.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import kr.ac.pusan.pickle.request.RequestStatus;
import org.jspecify.annotations.Nullable;

/** A pure observation of the existing workspace and invitation termination effects. */
public record WorkspaceTerminationImpactResponse(
        Action action,
        UUID workspaceId,
        String workspaceName,
        @Nullable UUID invitationId,
        @Schema(description = "조회한 시각이며, 실제 종료나 리소스 생성 결과를 뜻하지 않아요.")
        Instant observedAt,
        @Schema(description = "수행자와 현재 권한, 전체 영향 대상, 리소스 조건을 나타내는 식별값이에요. 페이지와 조회 시각은 포함하지 않아요.")
        String fingerprint,
        boolean executable,
        @Schema(description = "현재 종료를 막는 조건이에요. PERSONAL 또는 REMAINING_RESOURCES이며, 차단 조건이 없으면 null이에요.")
        @Nullable String blockedReason,
        WorkspaceTerminationSummary summary,
        List<WorkspaceTerminationRecipientCount> recipientCounts,
        List<WorkspaceTerminationResourceBlocker> resourceBlockers,
        PageResponse<WorkspaceTerminationResourceResponse> blockingResources,
        PageResponse<WorkspaceTerminationTargetResponse> targets) {

    @Schema(name = "WorkspaceTerminationAction")
    public enum Action { DELETE_WORKSPACE, CANCEL_INVITATION }

    @Schema(name = "WorkspaceTerminationSummary")
    public record WorkspaceTerminationSummary(
            long submittedRequests,
            long closingRecipients,
            @Schema(description = "자동으로 종료하지 않고 현재 실패 이력으로 남기는 대상자 수")
            long failedRecipients,
            long pendingJoinRecipients,
            long queuedRecipients,
            long approvedRecipients,
            long undecidedRecipients) { }

    @Schema(name = "WorkspaceTerminationRecipientCount")
    public record WorkspaceTerminationRecipientCount(RequestStatus requestStatus, RequestRecipientStatus recipientStatus,
            long count, boolean willClose) { }

    @Schema(name = "WorkspaceTerminationResourceBlocker")
    public record WorkspaceTerminationResourceBlocker(ResourceType type, long count) { }
}
