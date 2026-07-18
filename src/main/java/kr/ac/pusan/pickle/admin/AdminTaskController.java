package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import kr.ac.pusan.pickle.admin.dto.AdminTaskResponse;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskKind;
import kr.ac.pusan.pickle.provisioning.ProvisioningTaskStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, task queue subset (M5) — list of VM async tasks
 * and the NEEDS_ADMIN retry, SYS_ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/admin/tasks")
@PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
public class AdminTaskController {

    private final AdminTaskService adminTaskService;

    public AdminTaskController(AdminTaskService adminTaskService) {
        this.adminTaskService = adminTaskService;
    }

    @GetMapping
    public PageResponse<AdminTaskResponse> listAdminTasks(
            // Multi-value (v0.9.0): ?status=FAILED&status=NEEDS_ADMIN → OR filter;
            // a single value still binds to a one-element list (compat).
            @RequestParam(required = false) List<ProvisioningTaskStatus> status,
            @RequestParam(required = false) ProvisioningTaskKind kind,
            @RequestParam(required = false) Long vmId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminTaskService.list(status, kind, vmId, page, size);
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<MessageResponse> retryAdminTask(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long taskId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.accepted()
                .body(adminTaskService.retry(principal, taskId, clientIp(httpRequest)));
    }
}
