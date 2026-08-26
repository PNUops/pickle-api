package kr.ac.pusan.pickle.publishing;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin publishing views (contract tag {@code admin}): route/domain/certificate
 * listings (ORG_ADMIN self-org, SYS_ADMIN all), the sys-tier sync-all trigger,
 * and the post-hoc intervention ops (contract v0.18.0): force release, forced
 * re-verification, and single-route re-apply — all four admin roles with the
 * org tier limited to its own org's targets (cross-org answers 404).
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
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public PageResponse<AdminRouteView> listAdminRoutes(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) RouteStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminPublishingService.listRoutes(principal, orgId, status, page, size);
    }

    @GetMapping("/domains")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public PageResponse<AdminDomainView> listAdminDomains(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) DomainKind kind,
            @RequestParam(required = false) DomainStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminPublishingService.listDomains(principal, orgId, kind, status, page, size);
    }

    @GetMapping("/certificates")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    public PageResponse<AdminCertificateView> listAdminCertificates(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId,
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

    /** Immediate release of a problem domain (route removal + cert revocation). */
    @PostMapping("/domains/{domainId}/force-release")
    public MessageResponse forceReleaseDomain(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId, HttpServletRequest httpRequest) {
        return adminPublishingService.forceRelease(principal, domainId, clientIp(httpRequest));
    }

    /** Forced ownership re-verification of a custom domain (no user rate limit). */
    @PostMapping("/domains/{domainId}/verify")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse verifyAdminDomain(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId, HttpServletRequest httpRequest) {
        return adminPublishingService.verify(principal, domainId, clientIp(httpRequest));
    }

    /** Re-applies a single route's desired state to the proxy (generation bump). */
    @PostMapping("/routes/{routeId}/apply")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse applyAdminRoute(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID routeId, HttpServletRequest httpRequest) {
        return adminPublishingService.applyRoute(principal, routeId, clientIp(httpRequest));
    }
}
