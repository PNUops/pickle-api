package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.ForceDeleteVmRequest;
import kr.ac.pusan.pickle.admin.dto.ScheduleVmDeletionRequest;
import kr.ac.pusan.pickle.admin.dto.VmGatewayBlockUpdateRequest;
import kr.ac.pusan.pickle.admin.dto.VmPeriodUpdateRequest;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.VmDeletionService;
import kr.ac.pusan.pickle.vm.VmLifecycleService;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vm.dto.VmDeletionResponse;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmEventResponse;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, vms subset — list, detail/events, power
 * intervention, and deletion management. The org tier reads the organisations
 * it holds a role in and acts only in those it operates, with scheduled
 * deletion narrower still (administers); a target outside answers 404, never
 * 403, so the existence of other orgs stays private. Force delete and
 * the gateway-block toggle are SYS_ADMIN-only (method-level gates override the
 * class-level one).
 */
@RestController
@RequestMapping("/api/v1/admin/vms")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
public class AdminVmController {

    private final AdminVmQueryService adminVmQueryService;
    private final VmDeletionService vmDeletionService;
    private final VmPeriodService vmPeriodService;
    private final VmGatewayBlockService vmGatewayBlockService;
    private final VmLifecycleService vmLifecycleService;

    public AdminVmController(AdminVmQueryService adminVmQueryService,
            VmDeletionService vmDeletionService, VmPeriodService vmPeriodService,
            VmGatewayBlockService vmGatewayBlockService, VmLifecycleService vmLifecycleService) {
        this.adminVmQueryService = adminVmQueryService;
        this.vmDeletionService = vmDeletionService;
        this.vmPeriodService = vmPeriodService;
        this.vmGatewayBlockService = vmGatewayBlockService;
        this.vmLifecycleService = vmLifecycleService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public PageResponse<VmSummaryResponse> listAdminVms(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) VmStatus status,
            @RequestParam(required = false) @Min(1) Integer expiringInDays,
            @RequestParam(required = false) Boolean expired,
            @RequestParam(required = false) @Size(min = 1, max = 100) String q,
            @io.swagger.v3.oas.annotations.Parameter(schema = @io.swagger.v3.oas.annotations.media.Schema(
                    allowableValues = {"name", "-name", "endDate", "-endDate", "createdAt", "-createdAt"}))
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminVmQueryService.list(principal, orgId, workspaceId, status, expiringInDays, expired,
                q, sort, page, size);
    }

    /** Org-scoped admin view of the full VM detail (viewer is not a member). */
    @GetMapping("/{vmId}")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public VmDetailResponse getAdminVm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId) {
        return adminVmQueryService.get(principal, vmId);
    }

    @GetMapping("/{vmId}/events")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public PageResponse<VmEventResponse> listAdminVmEvents(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminVmQueryService.events(principal, vmId, page, size);
    }

    @PostMapping("/{vmId}/start")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse adminStartVm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId, HttpServletRequest httpRequest) {
        return vmLifecycleService.adminStart(principal, vmId, clientIp(httpRequest));
    }

    @PostMapping("/{vmId}/shutdown")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse adminShutdownVm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId, HttpServletRequest httpRequest) {
        return vmLifecycleService.adminShutdown(principal, vmId, clientIp(httpRequest));
    }

    @PostMapping("/{vmId}/reboot")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse adminRebootVm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId, HttpServletRequest httpRequest) {
        return vmLifecycleService.adminReboot(principal, vmId, clientIp(httpRequest));
    }

    @PostMapping("/{vmId}/force-stop")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse adminForceStopVm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId, HttpServletRequest httpRequest) {
        return vmLifecycleService.adminForceStop(principal, vmId, clientIp(httpRequest));
    }

    /** Synchronous DB update per contract (200 + full detail, not 202). */
    @PatchMapping("/{vmId}/period")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
    public VmDetailResponse updateVmPeriod(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId,
            @Valid @RequestBody VmPeriodUpdateRequest request,
            HttpServletRequest httpRequest) {
        return vmPeriodService.updatePeriod(principal, vmId, request, clientIp(httpRequest));
    }

    /**
     * Per-VM SSH-gateway/web-terminal kill switch — synchronous flag flip
     * (200 + full detail). SYS_ADMIN-only per the dangerous-op policy.
     */
    @PatchMapping("/{vmId}/gateway-block")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public VmDetailResponse updateVmGatewayBlock(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId,
            @Valid @RequestBody VmGatewayBlockUpdateRequest request,
            HttpServletRequest httpRequest) {
        return vmGatewayBlockService.updateBlock(principal, vmId, request, clientIp(httpRequest));
    }

    @PostMapping("/{vmId}/schedule-delete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VmDeletionResponse scheduleVmDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId,
            @Valid @RequestBody ScheduleVmDeletionRequest request,
            HttpServletRequest httpRequest) {
        return vmDeletionService.scheduleDeletion(principal, vmId, request,
                clientIp(httpRequest));
    }

    @PostMapping("/{vmId}/cancel-scheduled-delete")
    public MessageResponse cancelScheduledVmDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID vmId,
            HttpServletRequest httpRequest) {
        return vmDeletionService.cancelScheduledDeletion(principal, vmId, clientIp(httpRequest));
    }

    @PostMapping("/{vmId}/force-delete")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse forceDeleteVm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID vmId,
            @Valid @RequestBody ForceDeleteVmRequest request,
            HttpServletRequest httpRequest) {
        return vmDeletionService.forceDelete(principal, vmId, request,
                clientIp(httpRequest));
    }
}
