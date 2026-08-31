package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
 * Contract tag {@code admin}, vm-requests subset. The org tier reads the
 * organisations it holds a role in and decides in those it operates; the sys
 * tier covers all of them. Anything outside answers 404, never 403, so the
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
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
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
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public RequestDetailResponse getAdminRequest(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId) {
        return approvalService.get(principal, requestId);
    }

    @GetMapping("/{requestId}/context")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public ApprovalContextResponse getApprovalContext(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId) {
        return approvalContextService.context(principal, requestId);
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN')")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "승인 완료",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = RequestDetailResponse.class))),
        @ApiResponse(responseCode = "503",
                description = "OpenRouter account binding 전환 미활성 (`OPENROUTER_ACCOUNT_BINDING_DISABLED`)",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(ref = "#/components/schemas/Problem")))
    })
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
