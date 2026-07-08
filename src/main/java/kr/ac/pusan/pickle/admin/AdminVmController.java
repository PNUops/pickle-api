package kr.ac.pusan.pickle.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, VM list subset — ORG_ADMIN (own org) and
 * SYS_ADMIN (all orgs). Cross-org orgId filters answer 404, never 403, so
 * the existence of other orgs stays private.
 */
@RestController
@RequestMapping("/api/v1/admin/vms")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
public class AdminVmController {

    private final AdminVmQueryService adminVmQueryService;

    public AdminVmController(AdminVmQueryService adminVmQueryService) {
        this.adminVmQueryService = adminVmQueryService;
    }

    @GetMapping
    public PageResponse<VmSummaryResponse> listAdminVms(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) VmStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminVmQueryService.list(principal, orgId, groupId, status, page, size);
    }
}
