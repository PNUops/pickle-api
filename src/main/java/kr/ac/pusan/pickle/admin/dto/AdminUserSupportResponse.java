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

/** Student fields and each scoped relationship projection have independent work guards. */
public record AdminUserSupportResponse(
        UUID userId, Instant observedAt, @Nullable List<AdminUserSupportMembership> memberships,
        boolean invitationsVisible, @Nullable List<AdminUserSupportInvitation> invitations,
        @Nullable List<AdminUserSupportRelatedRequest> requests,
        @Schema(description = "학생 지원과 종류별 업무·대상 범위를 적용한 리소스 관계. 제공할 수 없는 진단 필드는 null입니다. 기관 선택은 필터입니다. 도메인은 EXTERNAL만 포함하며 VM 연결 도메인은 "
                + "워크스페이스 도메인 목록과 연결 VM에서 확인합니다. 종료된 리소스 이력도 포함합니다.")
        @Nullable List<AdminUserResourceAccessResponse> resources,
        @Schema(description = "관계 데이터가 업무나 대상 범위에 따라 일부만 제공되거나 제공되지 않았는지")
        boolean relationshipsLimited) {

    @Schema(name = "AdminUserSupportResourceCount")
    public record AdminUserSupportResourceCount(ResourceType type,
            @Schema(description = "공간과 기관 필터 안의 종류 전체 행 수를 읽을 수 있을 때만 제공하며 그 외에는 null입니다. 종료된 이력을 포함하며 도메인은 모든 종류를 셉니다.")
            @Nullable Long count) { }

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
