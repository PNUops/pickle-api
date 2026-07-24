package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse;
import kr.ac.pusan.pickle.admin.dto.ApproveVmRequestRequest;
import kr.ac.pusan.pickle.admin.dto.RejectVmRequestRequest;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vmrequest.VmRequestStatus;
import kr.ac.pusan.pickle.vmrequest.dto.VmRequestDetailResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, vm-requests subset — ORG_ADMIN (own org) and
 * SYS_ADMIN (all orgs). Cross-org requests answer 404, never 403, so the
 * existence of other orgs' requests stays private (contract v0.2.3).
 */
@RestController
@RequestMapping("/api/v1/admin/vm-requests")
// Read surfaces: org tier + sys tier (per the permission matrix). The approve/
// reject decisions drop SYS_MANAGER via a method-level override below.
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
public class AdminVmRequestController {

    private final ApprovalService approvalService;
    private final ApprovalContextService approvalContextService;

    public AdminVmRequestController(ApprovalService approvalService,
            ApprovalContextService approvalContextService) {
        this.approvalService = approvalService;
        this.approvalContextService = approvalContextService;
    }

    @GetMapping
    public PageResponse<VmRequestDetailResponse> listAdminVmRequests(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) VmRequestStatus status,
            @RequestParam(required = false) Long orgId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return approvalService.list(principal, status, orgId, page, size);
    }

    @GetMapping("/{requestId}")
    public VmRequestDetailResponse getAdminVmRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long requestId) {
        return approvalService.get(principal, requestId);
    }

    @GetMapping("/{requestId}/context")
    public ApprovalContextResponse getApprovalContext(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long requestId) {
        return approvalContextService.context(principal, requestId);
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN')")
    public VmRequestDetailResponse approveVmRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long requestId,
            @Valid @RequestBody ApproveVmRequestRequest request,
            HttpServletRequest httpRequest) {
        return approvalService.approve(principal, requestId, request, clientIp(httpRequest));
    }

    @PostMapping("/{requestId}/reject")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN')")
    public VmRequestDetailResponse rejectVmRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long requestId,
            @Valid @RequestBody RejectVmRequestRequest request,
            HttpServletRequest httpRequest) {
        return approvalService.reject(principal, requestId, request, clientIp(httpRequest));
    }
}
