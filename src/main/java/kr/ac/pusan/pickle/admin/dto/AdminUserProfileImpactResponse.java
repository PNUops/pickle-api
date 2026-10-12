package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;

/** Claim candidates and projected queue states, never a resource-completion receipt. */
public record AdminUserProfileImpactResponse(AdminUserProfileImpactRequest.AdminUserProfileImpactAction action,
        UUID userId, Instant observedAt, UserStatus candidateStatus, boolean claimsPossible,
        boolean studentNoWillChange, List<AdminUserProfileInvitationImpact> invitations) {

    @Schema(name = "AdminUserProfileInvitationImpact")
    public record AdminUserProfileInvitationImpact(UUID id, UUID workspaceId, String workspaceName,
            WorkspaceKind workspaceKind, WorkspaceMemberRole role, WorkspaceInvitationStatus status,
            String matchedBy, @Nullable Instant acceptedAt, boolean alreadyMember,
            List<AdminUserProfileRecipientImpact> recipients) { }

    @Schema(name = "AdminUserProfileRecipientImpact")
    public record AdminUserProfileRecipientImpact(UUID requestId, UUID orgId, ResourceType type, RequestStatus requestStatus,
            RequestRecipientStatus status, RequestRecipientStatus projectedStatus,
            @Nullable LocalDate grantedEndDate) { }
}
