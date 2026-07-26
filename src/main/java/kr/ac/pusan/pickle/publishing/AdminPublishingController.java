package kr.ac.pusan.pickle.publishing;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.publishing.dto.AdminCertificateView;
import kr.ac.pusan.pickle.publishing.dto.AdminDomainView;
import kr.ac.pusan.pickle.publishing.dto.AdminRouteView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin publishing views (contract tag {@code admin}): route/domain/certificate
 * listings (ORG_ADMIN self-org, SYS_ADMIN all) and the SYS_ADMIN sync-all
 * trigger. The resync is SYS_ADMIN-only because the manifest is authoritative
 * (platform-wide prune), so it is not exposed to org-scoped admins.
 */
@RestController
@RequestMapping("/api/v1/admin")
// Read surfaces: org tier + sys tier. resyncRoutes
// is a sys-tier-only routine recovery via a method-level override below.
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
public class AdminPublishingController {

    private final AdminPublishingService adminPublishingService;

    public AdminPublishingController(AdminPublishingService adminPublishingService) {
        this.adminPublishingService = adminPublishingService;
    }

    @GetMapping("/routes")
    public PageResponse<AdminRouteView> listAdminRoutes(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) RouteStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminPublishingService.listRoutes(principal, orgId, status, page, size);
    }

    @GetMapping("/domains")
    public PageResponse<AdminDomainView> listAdminDomains(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) DomainKind kind,
            @RequestParam(required = false) DomainStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminPublishingService.listDomains(principal, orgId, kind, status, page, size);
    }

    @GetMapping("/certificates")
    public PageResponse<AdminCertificateView> listAdminCertificates(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) CertificateStatus status,
            @RequestParam(required = false) @Min(1) Integer expiringInDays,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminPublishingService.listCertificates(principal, orgId, status, expiringInDays,
                page, size);
    }

    @PostMapping("/routes/resync")
    @PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resyncRoutes(
            @AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest httpRequest) {
        return adminPublishingService.resync(principal, clientIp(httpRequest));
    }
}
