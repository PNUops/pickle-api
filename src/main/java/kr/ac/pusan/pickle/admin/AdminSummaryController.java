package kr.ac.pusan.pickle.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.CapacityTrendResponse;
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
 * (ORG_ADMIN pinned to their org, SYS_ADMIN drills in via {@code orgId}), the
 * same panel over time (capacity trend, scoped identically) and the
 * SYS_ADMIN-only platform panel.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminSummaryController {

    private final AdminSummaryService adminSummaryService;
    private final CapacityTrendService capacityTrendService;

    public AdminSummaryController(AdminSummaryService adminSummaryService,
            CapacityTrendService capacityTrendService) {
        this.adminSummaryService = adminSummaryService;
        this.capacityTrendService = capacityTrendService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public OrgDashboardSummaryResponse getAdminSummary(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId) {
        return adminSummaryService.orgSummary(principal, orgId);
    }

    /** Daily allocation history against today's capacity (contract v0.35.0). */
    @GetMapping("/capacity-trend")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public CapacityTrendResponse getAdminCapacityTrend(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "90") @Min(7) @Max(365) int days,
            @RequestParam(required = false) UUID orgId) {
        return capacityTrendService.trend(principal, days, orgId);
    }

    @GetMapping("/system-summary")
    @PreAuthorize("hasAnyRole('SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public SystemDashboardSummaryResponse getSystemSummary() {
        return adminSummaryService.systemSummary();
    }
}
