package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;

/** Relationships retain directory visibility; resources and results retain institution scope. */
public record AdminUserSupportResponse(
        UUID userId, Instant observedAt, List<AdminUserSupportMembership> memberships,
        boolean invitationsVisible, List<AdminUserSupportInvitation> invitations,
        List<AdminUserSupportRelatedRequest> requests,
        @Schema(description = "조회 가능한 기관의 접근 목록형 리소스. 도메인은 EXTERNAL만 포함하며 VM 연결 도메인은 "
                + "워크스페이스 도메인 목록과 연결 VM에서 확인합니다. 종료된 리소스 이력도 포함합니다.")
        List<AdminUserResourceAccessResponse> resources) {

    @Schema(name = "AdminUserSupportResourceCount")
    public record AdminUserSupportResourceCount(ResourceType type,
            @Schema(description = "조회 가능한 기관의 종류별 행 수. 종료된 이력을 포함하며 도메인은 모든 종류를 셉니다.")
            long count) { }

    @Schema(name = "AdminUserSupportMembership")
    public record AdminUserSupportMembership(UUID workspaceId, String workspaceName, WorkspaceKind workspaceKind,
            WorkspaceMemberRole role, List<AdminUserSupportResourceCount> resourceCounts) { }

    @Schema(name = "AdminUserSupportInvitation")
    public record AdminUserSupportInvitation(UUID id, UUID workspaceId, String workspaceName,
            WorkspaceKind workspaceKind, WorkspaceMemberRole role, WorkspaceInvitationStatus status,
            String matchedBy, @Nullable Instant acceptedAt) { }

    @Schema(name = "AdminUserSupportRelatedRequest")
    public record AdminUserSupportRelatedRequest(UUID id, ResourceType type, RequestStatus status,
            UUID workspaceId, String workspaceName, UUID orgId, String orgName, boolean applicant,
            @Nullable UUID recipientId, @Nullable RequestRecipientStatus recipientStatus,
            @Nullable UUID resourceId, @Nullable LocalDate grantedEndDate, @Nullable String reason) { }
}
