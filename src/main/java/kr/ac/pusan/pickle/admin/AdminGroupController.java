package kr.ac.pusan.pickle.admin;

import java.util.List;
import kr.ac.pusan.pickle.admin.dto.AdminGroupOptionResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract {@code listAdminGroups} — group reference list for the
 * announcement screen. Small list, returned as a plain array (orgs/templates
 * convention).
 */
@RestController
@RequestMapping("/api/v1/admin/groups")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
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
}
