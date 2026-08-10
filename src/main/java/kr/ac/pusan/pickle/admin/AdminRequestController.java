package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.admin.dto.RejectRequestRequest;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.request.dto.RequestDetailResponse;
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
@RequestMapping("/api/v1/admin/requests")
// Read surfaces: org tier + sys tier (per the permission matrix). The approve/
// reject decisions drop SYS_MANAGER via a method-level override below.
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
public class AdminRequestController {

    private final ApprovalService approvalService;
    private final ApprovalContextService approvalContextService;

    public AdminRequestController(ApprovalService approvalService,
            ApprovalContextService approvalContextService) {
        this.approvalService = approvalService;
        this.approvalContextService = approvalContextService;
    }

    @GetMapping
    public PageResponse<RequestDetailResponse> listAdminRequests(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) ResourceType type,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return approvalService.list(principal, status, type, orgId, page, size);
    }

    @GetMapping("/{requestId}")
    public RequestDetailResponse getAdminRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId) {
        return approvalService.get(principal, requestId);
    }

    @GetMapping("/{requestId}/context")
    public ApprovalContextResponse getApprovalContext(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId) {
        return approvalContextService.context(principal, requestId);
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN')")
    public RequestDetailResponse approveRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId,
            @Valid @RequestBody ApproveRequestRequest request,
            HttpServletRequest httpRequest) {
        return approvalService.approve(principal, requestId, request, clientIp(httpRequest));
    }

    @PostMapping("/{requestId}/reject")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN')")
    public RequestDetailResponse rejectRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId,
            @Valid @RequestBody RejectRequestRequest request,
            HttpServletRequest httpRequest) {
        return approvalService.reject(principal, requestId, request, clientIp(httpRequest));
    }
}
