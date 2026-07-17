package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.admin.dto.ForceDeleteVmRequest;
import kr.ac.pusan.pickle.admin.dto.ScheduleVmDeletionRequest;
import kr.ac.pusan.pickle.admin.dto.VmPeriodUpdateRequest;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.VmDeletionService;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vm.dto.VmDeletionResponse;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, vms subset — list plus deletion management.
 * ORG_ADMIN acts on its own org (cross-org orgId filters and targets answer
 * 404, never 403, so the existence of other orgs stays private); SYS_ADMIN
 * covers all orgs. Force delete is SYS_ADMIN-only (method-level gate
 * overrides the class-level one).
 */
@RestController
@RequestMapping("/api/v1/admin/vms")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
public class AdminVmController {

    private final AdminVmQueryService adminVmQueryService;
    private final VmDeletionService vmDeletionService;
    private final VmPeriodService vmPeriodService;

    public AdminVmController(AdminVmQueryService adminVmQueryService,
            VmDeletionService vmDeletionService, VmPeriodService vmPeriodService) {
        this.adminVmQueryService = adminVmQueryService;
        this.vmDeletionService = vmDeletionService;
        this.vmPeriodService = vmPeriodService;
    }

    @GetMapping
    public PageResponse<VmSummaryResponse> listAdminVms(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) VmStatus status,
            @RequestParam(required = false) @Min(1) Integer expiringInDays,
            @RequestParam(required = false) Boolean expired,
            @RequestParam(required = false) @Size(min = 1, max = 100) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminVmQueryService.list(principal, orgId, groupId, status, expiringInDays, expired,
                q, sort, page, size);
    }

    /** Synchronous DB update per contract (200 + full detail, not 202). */
    @PatchMapping("/{vmId}/period")
    public VmDetailResponse updateVmPeriod(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            @Valid @RequestBody VmPeriodUpdateRequest request,
            HttpServletRequest httpRequest) {
        return vmPeriodService.updatePeriod(principal, vmId, request, clientIp(httpRequest));
    }

    @PostMapping("/{vmId}/schedule-delete")
    public ResponseEntity<VmDeletionResponse> scheduleVmDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            @Valid @RequestBody ScheduleVmDeletionRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.accepted()
                .body(vmDeletionService.scheduleDeletion(principal, vmId, request,
                        clientIp(httpRequest)));
    }

    @PostMapping("/{vmId}/cancel-scheduled-delete")
    public MessageResponse cancelScheduledVmDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            HttpServletRequest httpRequest) {
        return vmDeletionService.cancelScheduledDeletion(principal, vmId, clientIp(httpRequest));
    }

    @PostMapping("/{vmId}/force-delete")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ResponseEntity<MessageResponse> forceDeleteVm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            @Valid @RequestBody ForceDeleteVmRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.accepted()
                .body(vmDeletionService.forceDelete(principal, vmId, request,
                        clientIp(httpRequest)));
    }
}
