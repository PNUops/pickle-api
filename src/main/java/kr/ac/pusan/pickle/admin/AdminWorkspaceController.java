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

    public AdminWorkspaceController(AdminWorkspaceQueryService adminWorkspaceQueryService) {
        this.adminWorkspaceQueryService = adminWorkspaceQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public List<AdminWorkspaceOptionResponse> listAdminWorkspaces(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId,
            @Parameter(description = "true면 기관 필터와 무관하게 삭제되지 않은 모든 워크스페이스를 돌려줍니다. "
                    + "관리자가 대상자를 골라 신청할 워크스페이스를 고를 때 씁니다.")
            @RequestParam(required = false, defaultValue = "false") boolean all) {
        return adminWorkspaceQueryService.list(principal, all ? null : orgId, all);
    }

    @GetMapping("/{workspaceId}")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public AdminWorkspaceDetailResponse getAdminWorkspace(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return adminWorkspaceQueryService.get(principal, workspaceId);
    }

    @GetMapping("/{workspaceId}/invitations")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "워크스페이스의 대기 중인 초대 목록",
            description = "신청을 승인할 수 있는 관리자가 아직 가입하지 않은 사람을 신청 대상자로 고를 때 씁니다. "
                    + "대기 중인 초대만 오래된 순서로 돌려줍니다.")
    public List<WorkspaceInvitationResponse> listAdminWorkspaceInvitations(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return adminWorkspaceQueryService.listInvitations(principal, workspaceId);
    }
}
