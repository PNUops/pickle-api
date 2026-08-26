package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.DriftFindingResponse;
import kr.ac.pusan.pickle.admin.dto.ResolveDriftFindingRequest;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.provisioning.DriftFindingKind;
import kr.ac.pusan.pickle.provisioning.DriftFindingStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
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
 * Contract tag {@code admin}, drift report subset — findings persisted by
 * {@link kr.ac.pusan.pickle.provisioning.DriftReconciler}, SYS_ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/admin/drift-findings")
@PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
public class AdminDriftController {

    private final AdminDriftService adminDriftService;

    public AdminDriftController(AdminDriftService adminDriftService) {
        this.adminDriftService = adminDriftService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public PageResponse<DriftFindingResponse> listDriftFindings(
            @RequestParam(required = false) DriftFindingStatus status,
            @RequestParam(required = false) DriftFindingKind kind,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminDriftService.list(status, kind, page, size);
    }

    @PostMapping("/{findingId}/resolve")
    public DriftFindingResponse resolveDriftFinding(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID findingId,
            @Valid @RequestBody(required = false) ResolveDriftFindingRequest request,
            HttpServletRequest httpRequest) {
        return adminDriftService.resolve(principal, findingId, request, clientIp(httpRequest));
    }
}
