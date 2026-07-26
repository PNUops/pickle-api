package kr.ac.pusan.pickle.vm;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.dto.VmDeletionResponse;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmEventResponse;
import kr.ac.pusan.pickle.vm.dto.VmPasswordResponse;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import kr.ac.pusan.pickle.vmsettings.dto.VmSettingView;
import kr.ac.pusan.pickle.vmsettings.dto.VmSettingsUpdateRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code vms} (openapi.yaml v0.3.2, server /api/v1). */
@RestController
@RequestMapping("/api/v1/vms")
public class VmController {

    private final VmQueryService vmQueryService;
    private final VmLifecycleService vmLifecycleService;
    private final VmDeletionService vmDeletionService;
    private final VmPasswordService vmPasswordService;
    private final VmSettingsService vmSettingsService;

    public VmController(VmQueryService vmQueryService, VmLifecycleService vmLifecycleService,
            VmDeletionService vmDeletionService, VmPasswordService vmPasswordService,
            VmSettingsService vmSettingsService) {
        this.vmQueryService = vmQueryService;
        this.vmLifecycleService = vmLifecycleService;
        this.vmDeletionService = vmDeletionService;
        this.vmPasswordService = vmPasswordService;
        this.vmSettingsService = vmSettingsService;
    }

    @GetMapping
    public PageResponse<VmSummaryResponse> listVms(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return vmQueryService.list(principal, groupId, page, size);
    }

    @GetMapping("/{vmId}")
    public VmDetailResponse getVm(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId) {
        return vmQueryService.get(principal, vmId);
    }

    @DeleteMapping("/{vmId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VmDeletionResponse deleteVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            HttpServletRequest httpRequest) {
        return vmDeletionService.selfDelete(principal, vmId, clientIp(httpRequest));
    }

    @PostMapping("/{vmId}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse startVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return vmLifecycleService.start(principal, vmId);
    }

    @PostMapping("/{vmId}/shutdown")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse shutdownVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return vmLifecycleService.shutdown(principal, vmId);
    }

    @PostMapping("/{vmId}/reboot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse rebootVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return vmLifecycleService.reboot(principal, vmId);
    }

    @PostMapping("/{vmId}/force-stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse forceStopVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return vmLifecycleService.forceStop(principal, vmId);
    }

    @GetMapping("/{vmId}/events")
    public PageResponse<VmEventResponse> listVmEvents(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return vmQueryService.events(principal, vmId, page, size);
    }

    /** Re-viewable reveal (GET since v0.7.0 — no side effect); never cached. */
    @GetMapping("/{vmId}/password")
    public ResponseEntity<VmPasswordResponse> revealVmPassword(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            HttpServletRequest httpRequest) {
        VmPasswordResponse response =
                vmPasswordService.reveal(principal, vmId, clientIp(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    /** Regenerates the password live via the guest agent (EDITOR+); never cached. */
    @PostMapping("/{vmId}/password/regenerate")
    public ResponseEntity<VmPasswordResponse> regenerateVmPassword(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            HttpServletRequest httpRequest) {
        VmPasswordResponse response =
                vmPasswordService.regenerate(principal, vmId, clientIp(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    /** Per-VM settings (EDITOR+; non-member 404). */
    @GetMapping("/{vmId}/settings")
    public List<VmSettingView> getVmSettings(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId) {
        return vmSettingsService.get(principal, vmId);
    }

    /** Atomic partial update; per-key required role (contract v0.8.0). */
    @PatchMapping("/{vmId}/settings")
    public List<VmSettingView> updateVmSettings(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            @Valid @RequestBody VmSettingsUpdateRequest request, HttpServletRequest httpRequest) {
        return vmSettingsService.patch(principal, vmId, request.settings(), clientIp(httpRequest));
    }

}
