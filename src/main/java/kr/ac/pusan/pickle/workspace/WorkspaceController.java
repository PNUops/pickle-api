package kr.ac.pusan.pickle.workspace;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import kr.ac.pusan.pickle.workspace.dto.AddWorkspaceMemberRequest;
import kr.ac.pusan.pickle.workspace.dto.CreateWorkspaceRequest;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceDetailResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceMemberResponse;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceSummaryResponse;
import kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceMemberRequest;
import kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
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

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
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
            @PathVariable long workspaceId) {
        return workspaceService.get(principal, workspaceId);
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceDetailResponse updateWorkspace(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long workspaceId,
            @Valid @RequestBody UpdateWorkspaceRequest request) {
        return workspaceService.update(principal, workspaceId, request);
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkspace(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long workspaceId,
            HttpServletRequest httpRequest) {
        workspaceService.delete(principal, workspaceId, clientIp(httpRequest));
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireReauth
    public WorkspaceMemberResponse addWorkspaceMember(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long workspaceId,
            @Valid @RequestBody AddWorkspaceMemberRequest request,
            HttpServletRequest httpRequest) {
        return workspaceService.addMember(principal, workspaceId, request, clientIp(httpRequest));
    }

    @PatchMapping("/{workspaceId}/members/{userId}")
    @RequireReauth
    public WorkspaceMemberResponse updateWorkspaceMember(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long workspaceId,
            @PathVariable long userId,
            @Valid @RequestBody UpdateWorkspaceMemberRequest request,
            HttpServletRequest httpRequest) {
        return workspaceService.updateMemberRole(principal, workspaceId, userId, request, clientIp(httpRequest));
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireReauth
    public void removeWorkspaceMember(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long workspaceId,
            @PathVariable long userId,
            HttpServletRequest httpRequest) {
        workspaceService.removeMember(principal, workspaceId, userId, clientIp(httpRequest));
    }
}
