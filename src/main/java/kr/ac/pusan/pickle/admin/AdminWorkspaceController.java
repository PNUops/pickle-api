package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceInvitationResponse;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceDetailResponse;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceOptionResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract {@code listAdminWorkspaces} — workspace reference list shared by the
 * announcement picker and the admin workspace screen (plain array,
 * orgs/os-images convention) — plus the v0.19.0 inspection detail
 * {@code getAdminWorkspace} (members incl. non-ACTIVE accounts, VM count).
 */
@RestController
@RequestMapping("/api/v1/admin/workspaces")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
public class AdminWorkspaceController {

    private final AdminWorkspaceQueryService adminWorkspaceQueryService;
    private final kr.ac.pusan.pickle.workspace.WorkspaceService workspaces;
    private final kr.ac.pusan.pickle.workspace.WorkspaceInvitationService invitations;

    public AdminWorkspaceController(AdminWorkspaceQueryService adminWorkspaceQueryService, kr.ac.pusan.pickle.workspace.WorkspaceService workspaces, kr.ac.pusan.pickle.workspace.WorkspaceInvitationService invitations) {
        this.adminWorkspaceQueryService = adminWorkspaceQueryService; this.workspaces = workspaces; this.invitations = invitations;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public List<AdminWorkspaceOptionResponse> listAdminWorkspaces(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId,
            @Parameter(description = "true면 기관 필터와 무관하게 삭제되지 않은 모든 워크스페이스를 돌려줍니다. "
                    + "관리자가 대상자를 골라 신청할 워크스페이스를 고를 때 씁니다.")
            @RequestParam(required = false, defaultValue = "false") boolean all) {
        return adminWorkspaceQueryService.list(principal, orgId, all);
    }

    @GetMapping("/{workspaceId}")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public AdminWorkspaceDetailResponse getAdminWorkspace(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return adminWorkspaceQueryService.get(principal, workspaceId);
    }

    @GetMapping("/{workspaceId}/invitations")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "워크스페이스의 대기 중인 초대 목록",
            description = "학생 지원 권한으로 아직 가입하지 않은 대상의 초대 정보를 조회합니다. "
                    + "대기 중인 초대만 오래된 순서로 돌려줍니다.")
    public List<WorkspaceInvitationResponse> listAdminWorkspaceInvitations(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return adminWorkspaceQueryService.listInvitations(principal, workspaceId);
    }
    @org.springframework.web.bind.annotation.PatchMapping("/{workspaceId}")
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public AdminWorkspaceDetailResponse updateAdminWorkspace(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID workspaceId, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceRequest request, jakarta.servlet.http.HttpServletRequest http) {
        workspaces.update(actor, workspaceId, request, kr.ac.pusan.pickle.common.web.ClientIps.clientIp(http), true);
        return adminWorkspaceQueryService.get(actor, workspaceId);
    }
    @org.springframework.web.bind.annotation.PostMapping("/{workspaceId}/members/{userId}/preview")
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public kr.ac.pusan.pickle.access.dto.ActorAccessImpact previewAdminWorkspaceMemberRole(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID workspaceId, @PathVariable UUID userId, @jakarta.validation.Valid
            @org.springframework.web.bind.annotation.RequestBody kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceMemberRequest request) {
        return workspaces.previewMemberRole(actor, workspaceId, userId, request);
    }
    @org.springframework.web.bind.annotation.PatchMapping("/{workspaceId}/members/{userId}")
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public kr.ac.pusan.pickle.workspace.dto.WorkspaceMemberResponse updateAdminWorkspaceMemberRole(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID workspaceId, @PathVariable UUID userId,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceMemberRequest request,
            jakarta.servlet.http.HttpServletRequest http) {
        return workspaces.updateMemberRole(actor, workspaceId, userId, request, kr.ac.pusan.pickle.common.web.ClientIps.clientIp(http), true);
    }
    @org.springframework.web.bind.annotation.DeleteMapping("/{workspaceId}/members/{userId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public void removeAdminWorkspaceMember(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID workspaceId,
            @PathVariable UUID userId, jakarta.servlet.http.HttpServletRequest http) {
        workspaces.removeMember(actor, workspaceId, userId, kr.ac.pusan.pickle.common.web.ClientIps.clientIp(http), true);
    }
    @org.springframework.web.bind.annotation.PostMapping("/{workspaceId}/invitations/preview")
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public kr.ac.pusan.pickle.access.dto.ActorAccessImpact previewAdminWorkspaceInvitations(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID workspaceId, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersRequest request) {
        return invitations.previewInvite(actor, workspaceId, request, true);
    }
    @org.springframework.web.bind.annotation.PostMapping("/{workspaceId}/invitations")
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersResponse inviteAdminWorkspaceMembers(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID workspaceId, @jakarta.validation.Valid
            @org.springframework.web.bind.annotation.RequestBody kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersRequest request,
            jakarta.servlet.http.HttpServletRequest http) {
        return invitations.invite(actor, workspaceId, request, kr.ac.pusan.pickle.common.web.ClientIps.clientIp(http), true);
    }
    @org.springframework.web.bind.annotation.DeleteMapping("/{workspaceId}/invitations/{invitationId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public void cancelAdminWorkspaceInvitation(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID workspaceId,
            @PathVariable UUID invitationId, jakarta.servlet.http.HttpServletRequest http) {
        invitations.cancel(actor, workspaceId, invitationId, kr.ac.pusan.pickle.common.web.ClientIps.clientIp(http), true);
    }
    @org.springframework.web.bind.annotation.DeleteMapping("/{workspaceId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ORG_MANAGER','ORG_ADMIN','SYS_ADMIN')")
    public void deleteAdminWorkspace(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID workspaceId,
            jakarta.servlet.http.HttpServletRequest http) {
        workspaces.delete(actor, workspaceId, kr.ac.pusan.pickle.common.web.ClientIps.clientIp(http), true);
    }

}
