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
import kr.ac.pusan.pickle.networkpolicy.dto.SourcePolicyView;
import kr.ac.pusan.pickle.networkpolicy.dto.UpdateSourcePolicyRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_ADMIN', 'SYS_MANAGER')")
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
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) DomainKind kind,
            @RequestParam(required = false) DomainStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return adminPublishingService.listDomains(principal, orgId, workspaceId, kind, status, page, size);
    }

    @GetMapping("/domains/{domainId}")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(operationId = "getAdminDomain", summary = "도메인 단건 열람",
            description = "학생 지원 권한으로 정확한 도메인 ID의 현재 저장 metadata를 전역 조회해요. "
                    + "목록과 같은 기간과 해제, DNS 상태를 반환하며 외부 조회나 변경은 실행하지 않아요. "
                    + "삭제된 도메인과 기관 및 워크스페이스의 필수 식별 정보를 확인할 수 없는 도메인은 404로 응답해요.")
    public AdminDomainView getAdminDomain(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId) {
        return adminPublishingService.getDomain(principal, domainId);
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
            description = "허용된 루트 도메인과 승인 정책을 조회해요. "
                    + "전체 카탈로그 조회 권한이 있으면 모든 루트를 볼 수 있어요. 기관별 정책 조회나 변경 업무만 있으면 "
                    + "해당 기관 범위의 루트만 보여요.")
    public List<AdminDomainRootView> listDomainRoots(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return adminDomainRootService.list(principal);
    }

    @PatchMapping("/domain-roots/{rootDomain}")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "루트 도메인 승인 정책 변경",
            description = "이 루트 아래 이름 신청을 자동 승인할지 승인 큐에 넣을지 정해요. "
                    + "앞으로 들어오는 신청에만 적용되며 이미 발급된 이름과 이미 기다리는 신청은 "
                    + "바꾸지 않아요.")
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
            description = "이 이름이 현재 무엇을 가리키는지 조회해요. 읽기 전용이에요.")
    public List<DnsRecordSetView> listAdminDomainRecords(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId) {
        return adminPublishingService.listRecords(principal, domainId);
    }

    @PatchMapping("/domains/{domainId}/renewal")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "도메인 사용 기한 조정",
            description = "외부 도메인의 사용 기한을 바꿔요. 미루면 그만큼 더 쓰고 당기면 "
                    + "새 기한이 지난 뒤 연장하지 않은 이름은 해제 대상이 돼요.")
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

    @GetMapping("/routes/{routeId}/source-policy")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(operationId = "getAdminRouteSourcePolicy", summary = "라우트 출발지 정책 조회")
    public SourcePolicyView getAdminRouteSourcePolicy(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID routeId) {
        return adminPublishingService.getRouteSourcePolicy(principal, routeId);
    }

    @PutMapping("/routes/{routeId}/source-policy")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "updateAdminRouteSourcePolicy", summary = "라우트 출발지 정책 저장")
    public SourcePolicyView updateAdminRouteSourcePolicy(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID routeId,
            @Valid @RequestBody UpdateSourcePolicyRequest request,
            HttpServletRequest httpRequest) {
        return adminPublishingService.updateRouteSourcePolicy(principal, routeId,
                request.expectedRevision(), request.allowedCidrs(), clientIp(httpRequest));
    }
}
