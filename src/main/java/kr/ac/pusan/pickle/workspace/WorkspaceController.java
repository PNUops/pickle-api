package kr.ac.pusan.pickle.workspace;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.request.RosterService;
import kr.ac.pusan.pickle.request.dto.ResolveRosterRequest;
import kr.ac.pusan.pickle.request.dto.ResolveRosterResponse;
import kr.ac.pusan.pickle.workspace.dto.CreateWorkspaceRequest;
import kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersRequest;
import kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceDetailResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceInvitationResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceMemberResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceSummaryResponse;
import kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceMemberRequest;
import kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code workspaces} (openapi.yaml v0.2.1, server /api/v1). */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceInvitationService invitationService;
    private final RosterService rosterService;

    public WorkspaceController(WorkspaceService workspaceService, WorkspaceInvitationService invitationService,
            RosterService rosterService) {
        this.workspaceService = workspaceService;
        this.invitationService = invitationService;
        this.rosterService = rosterService;
    }

    @GetMapping
    public List<WorkspaceSummaryResponse> listWorkspaces(@AuthenticationPrincipal AuthenticatedUser principal) {
        return workspaceService.listMyWorkspaces(principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceDetailResponse createWorkspace(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateWorkspaceRequest request,
            HttpServletRequest httpRequest) {
        return workspaceService.create(principal, request, clientIp(httpRequest));
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceDetailResponse getWorkspace(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return workspaceService.get(principal, workspaceId);
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceDetailResponse updateWorkspace(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWorkspaceRequest request,
            HttpServletRequest httpRequest) {
        return workspaceService.update(principal, workspaceId, request, clientIp(httpRequest));
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkspace(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            HttpServletRequest httpRequest) {
        workspaceService.delete(principal, workspaceId, clientIp(httpRequest));
    }

    @PostMapping("/{workspaceId}/invitations")
    @Operation(summary = "구성원 초대",
            description = "이메일이나 학번으로 한 번에 1명에서 200명까지 초대합니다. 활성 계정이 있으면 바로 "
                    + "구성원(MEMBER)이 되고, 없으면 초대가 대기하다가 그 사람이 가입하거나 학번을 등록하면 "
                    + "자동으로 구성원이 됩니다. 초대는 만료되지 않으며 소유자가 취소할 수 있습니다. "
                    + "결과는 항목마다 요청과 같은 순서로 옵니다. 소유자(OWNER)만 호출할 수 있고 "
                    + "개인 워크스페이스에는 초대할 수 없습니다.")
    public InviteWorkspaceMembersResponse inviteWorkspaceMembers(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody InviteWorkspaceMembersRequest request,
            HttpServletRequest httpRequest) {
        return invitationService.invite(principal, workspaceId, request, clientIp(httpRequest));
    }

    @GetMapping("/{workspaceId}/invitations")
    @Operation(summary = "대기 중인 초대 목록",
            description = "아직 받지 않은 초대만 오래된 순서로 돌려줍니다. 소유자(OWNER)만 볼 수 있습니다.")
    public List<WorkspaceInvitationResponse> listWorkspaceInvitations(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return invitationService.list(principal, workspaceId);
    }

    @DeleteMapping("/{workspaceId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "초대 취소",
            description = "대기 중인 초대를 취소합니다. 이미 받았거나 취소한 초대는 찾을 수 없음(404)으로 "
                    + "답합니다. 소유자(OWNER)만 호출할 수 있습니다.")
    public void cancelWorkspaceInvitation(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID invitationId,
            HttpServletRequest httpRequest) {
        invitationService.cancel(principal, workspaceId, invitationId, clientIp(httpRequest));
    }

    @PostMapping("/{workspaceId}/roster/resolve")
    @Operation(summary = "학번 명단 확인",
            description = "신청 대상자로 지정할 학번 명단을 제출하기 전에 확인합니다. 학번마다 이미 구성원인지, 활성 계정이 "
                    + "있지만 구성원이 아닌지, 대기 중인 초대가 있는지, 계정도 초대도 없는지를 요청과 같은 순서로 "
                    + "돌려줍니다. 아무것도 기록하지 않으며, 구성원 추가와 초대는 학번 대상자로 신청을 제출할 때 "
                    + "일어납니다. 워크스페이스 소유자나, orgId로 지정한 기관의 신청을 승인할 수 있는 관리자만 "
                    + "호출할 수 있고 개인 워크스페이스에는 쓸 수 없습니다. 한 번에 500건까지, 1분에 10번까지입니다.")
    public ResolveRosterResponse resolveWorkspaceRoster(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody ResolveRosterRequest request) {
        return rosterService.resolve(principal, workspaceId, request);
    }

    @PatchMapping("/{workspaceId}/members/{userId}")
    public WorkspaceMemberResponse updateWorkspaceMember(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateWorkspaceMemberRequest request,
            HttpServletRequest httpRequest) {
        return workspaceService.updateMemberRole(principal, workspaceId, userId, request, clientIp(httpRequest));
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeWorkspaceMember(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId,
            HttpServletRequest httpRequest) {
        workspaceService.removeMember(principal, workspaceId, userId, clientIp(httpRequest));
    }
}
