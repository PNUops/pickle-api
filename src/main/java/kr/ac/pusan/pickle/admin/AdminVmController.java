package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.admin.dto.EmergencyDeleteVmRequest;
import kr.ac.pusan.pickle.admin.dto.ScheduleVmDeletionRequest;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.VmDeletionService;
import kr.ac.pusan.pickle.vm.dto.VmDeletionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, vms deletion subset — ORG_ADMIN (own org,
 * cross-org 404 masking) and SYS_ADMIN; emergency delete is SYS_ADMIN-only
 * (method-level gate overrides the class-level one).
 */
@RestController
@RequestMapping("/api/v1/admin/vms")
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'SYS_ADMIN')")
public class AdminVmController {

    private final VmDeletionService vmDeletionService;

    public AdminVmController(VmDeletionService vmDeletionService) {
        this.vmDeletionService = vmDeletionService;
    }

    @PostMapping("/{vmId}/schedule-delete")
    public ResponseEntity<VmDeletionResponse> scheduleVmDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            @Valid @RequestBody ScheduleVmDeletionRequest request) {
        return ResponseEntity.accepted()
                .body(vmDeletionService.scheduleDeletion(principal, vmId, request));
    }

    @PostMapping("/{vmId}/cancel-scheduled-delete")
    public MessageResponse cancelScheduledVmDeletion(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long vmId) {
        return vmDeletionService.cancelScheduledDeletion(principal, vmId);
    }

    @PostMapping("/{vmId}/emergency-delete")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ResponseEntity<MessageResponse> emergencyDeleteVm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            @Valid @RequestBody EmergencyDeleteVmRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.accepted()
                .body(vmDeletionService.emergencyDelete(principal, vmId, request,
                        clientIp(httpRequest)));
    }
}
