package kr.ac.pusan.pickle.admin;

import java.util.List;
import kr.ac.pusan.pickle.admin.dto.AdminGroupDetailResponse;
import kr.ac.pusan.pickle.admin.dto.AdminGroupOptionResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract {@code listAdminGroups} — group reference list shared by the
 * announcement picker and the admin group screen (plain array,
 * orgs/os-images convention) — plus the v0.19.0 inspection detail
 * {@code getAdminGroup} (members incl. non-ACTIVE accounts, VM count).
 */
@RestController
@RequestMapping("/api/v1/admin/groups")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
public class AdminGroupController {

    private final AdminGroupQueryService adminGroupQueryService;

    public AdminGroupController(AdminGroupQueryService adminGroupQueryService) {
        this.adminGroupQueryService = adminGroupQueryService;
    }

    @GetMapping
    public List<AdminGroupOptionResponse> listAdminGroups(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long orgId) {
        return adminGroupQueryService.list(principal, orgId);
    }

    @GetMapping("/{groupId}")
    public AdminGroupDetailResponse getAdminGroup(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long groupId) {
        return adminGroupQueryService.get(principal, groupId);
    }
}
