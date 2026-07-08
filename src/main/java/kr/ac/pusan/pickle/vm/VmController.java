package kr.ac.pusan.pickle.vm;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.dto.InitialPasswordResponse;
import kr.ac.pusan.pickle.vm.dto.VmDeletionResponse;
import kr.ac.pusan.pickle.vm.dto.VmDetailResponse;
import kr.ac.pusan.pickle.vm.dto.VmSummaryResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code vms} (openapi.yaml v0.3.1, server /api/v1). */
@RestController
@RequestMapping("/api/v1/vms")
public class VmController {

    private final VmQueryService vmQueryService;
    private final VmLifecycleService vmLifecycleService;
    private final VmDeletionService vmDeletionService;
    private final InitialPasswordService initialPasswordService;

    public VmController(VmQueryService vmQueryService, VmLifecycleService vmLifecycleService,
            VmDeletionService vmDeletionService, InitialPasswordService initialPasswordService) {
        this.vmQueryService = vmQueryService;
        this.vmLifecycleService = vmLifecycleService;
        this.vmDeletionService = vmDeletionService;
        this.initialPasswordService = initialPasswordService;
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
    public ResponseEntity<VmDeletionResponse> deleteVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return ResponseEntity.accepted().body(vmDeletionService.selfDelete(principal, vmId));
    }

    @PostMapping("/{vmId}/start")
    public ResponseEntity<MessageResponse> startVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return ResponseEntity.accepted().body(vmLifecycleService.start(principal, vmId));
    }

    @PostMapping("/{vmId}/shutdown")
    public ResponseEntity<MessageResponse> shutdownVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return ResponseEntity.accepted().body(vmLifecycleService.shutdown(principal, vmId));
    }

    @PostMapping("/{vmId}/reboot")
    public ResponseEntity<MessageResponse> rebootVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return ResponseEntity.accepted().body(vmLifecycleService.reboot(principal, vmId));
    }

    @PostMapping("/{vmId}/force-stop")
    public ResponseEntity<MessageResponse> forceStopVm(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return ResponseEntity.accepted().body(vmLifecycleService.forceStop(principal, vmId));
    }

    /** One-shot reveal (POST — consuming side effect); response must never cache. */
    @PostMapping("/{vmId}/initial-password")
    public ResponseEntity<InitialPasswordResponse> revealInitialPassword(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId,
            HttpServletRequest httpRequest) {
        InitialPasswordResponse response =
                initialPasswordService.reveal(principal, vmId, clientIp(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }
}
