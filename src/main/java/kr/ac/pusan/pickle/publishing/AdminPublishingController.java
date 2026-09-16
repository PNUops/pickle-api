package kr.ac.pusan.pickle.publishing;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.publishing.dto.AdminCertificateView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import kr.ac.pusan.pickle.publishing.dto.AdminDomainRootView;
import kr.ac.pusan.pickle.publishing.dto.AdminDomainView;
import kr.ac.pusan.pickle.publishing.dto.DnsRecordSetView;
import kr.ac.pusan.pickle.publishing.dto.UpdateDomainRenewalRequest;
import kr.ac.pusan.pickle.publishing.dto.UpdateDomainRootRequest;
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
 * listings (the org tier for the organisations it holds a role in, the sys tier
 * for all), the sys-tier sync-all trigger, and the post-hoc intervention ops
 * (contract v0.18.0): force release, forced re-verification, and single-route
 * re-apply — open to every admin role that may act, with the org tier limited
 * to the organisations it operates (anything outside answers 404).
 */
@RestController
@RequestMapping("/api/v1/admin")
// Read surfaces: org tier + sys tier. resyncRoutes
// is a sys-tier-only routine recovery via a method-level override below.
@PreAuthorize("hasAnyRole('ORG_ADMIN', 'ORG_MANAGER', 'SYS_ADMIN', 'SYS_MANAGER')")
public class AdminPublishingController {

    private final AdminPublishingService adminPublishingService;
    private final AdminDomainRootService adminDomainRootService;

    public AdminPublishingController(AdminPublishingService adminPublishingService,
            AdminDomainRootService adminDomainRootService) {
        this.adminPublishingService = adminPublishingService;
        this.adminDomainRootService = adminDomainRootService;
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

    @GetMapping("/domain-roots")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "루트 도메인 목록",
            description = "이름을 발급할 수 있는 루트와 그 승인 정책. 기관으로 좁히지 않습니다 — "
                    + "이름 공간은 기관끼리 공유하므로, 자기 루트의 정책을 정하는 사람도 다른 "
                    + "루트가 있다는 것은 볼 수 있어야 합니다.")
    public List<AdminDomainRootView> listDomainRoots(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return adminDomainRootService.list(principal);
    }

    @PatchMapping("/domain-roots/{rootDomain}")
    @PreAuthorize("hasAnyRole('ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "루트 도메인 승인 정책 변경",
            description = "이 루트 아래 이름 신청을 자동으로 승인할지, 승인 큐에 세울지 정합니다. "
                    + "앞으로 들어오는 신청에만 적용되며 이미 발급된 이름과 이미 기다리는 신청은 "
                    + "건드리지 않습니다.")
    public AdminDomainRootView updateDomainRoot(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String rootDomain,
            @Valid @RequestBody UpdateDomainRootRequest request,
            HttpServletRequest httpRequest) {
        return adminDomainRootService.update(principal, rootDomain, request,
                clientIp(httpRequest));
    }

    @GetMapping("/domains/{domainId}/records")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "도메인 레코드 열람",
            description = "이 이름이 지금 무엇을 가리키는지 봅니다. 읽기 전용입니다.")
    public List<DnsRecordSetView> listAdminDomainRecords(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId) {
        return adminPublishingService.listRecords(principal, domainId);
    }

    @PatchMapping("/domains/{domainId}/renewal")
    @PreAuthorize("hasAnyRole('ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "도메인 사용 기한 조정",
            description = "외부 도메인의 사용 기한을 옮깁니다. 미루면 그만큼 더 쓰고, 당기면 "
                    + "그 시점에 연장하지 않는 한 이름이 해제됩니다.")
    public AdminDomainView updateDomainRenewal(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId,
            @Valid @RequestBody UpdateDomainRenewalRequest request,
            HttpServletRequest httpRequest) {
        return adminPublishingService.updateRenewal(principal, domainId, request,
                clientIp(httpRequest));
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
