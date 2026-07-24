package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.admin.dto.OrgDashboardSummaryResponse;
import kr.ac.pusan.pickle.admin.dto.SystemDashboardSummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, dashboard summaries — the org panel
 * (ORG_ADMIN pinned to their org, SYS_ADMIN drills in via {@code orgId}) and
 * the SYS_ADMIN-only platform panel.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminSummaryController {

    private final AdminSummaryService adminSummaryService;

    public AdminSummaryController(AdminSummaryService adminSummaryService) {
        this.adminSummaryService = adminSummaryService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
    public OrgDashboardSummaryResponse getAdminSummary(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long orgId) {
        return adminSummaryService.orgSummary(principal, orgId);
    }

    @GetMapping("/system-summary")
    @PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
    public SystemDashboardSummaryResponse getSystemSummary() {
        return adminSummaryService.systemSummary();
    }
}
