package kr.ac.pusan.pickle.admin;

import java.util.List;
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
            @RequestParam(required = false) UUID orgId) {
        return adminWorkspaceQueryService.list(principal, orgId);
    }

    @GetMapping("/{workspaceId}")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public AdminWorkspaceDetailResponse getAdminWorkspace(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return adminWorkspaceQueryService.get(principal, workspaceId);
    }
}
