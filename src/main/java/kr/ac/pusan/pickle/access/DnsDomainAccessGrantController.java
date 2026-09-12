package kr.ac.pusan.pickle.access;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import kr.ac.pusan.pickle.access.dto.AddResourceAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.ResourceAccessGrantView;
import kr.ac.pusan.pickle.access.dto.ResourceAccessListResponse;
import kr.ac.pusan.pickle.access.dto.UpdateResourceAccessGrantRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code dns-domain-access}: who may reach one issued name, and at
 * what rung.
 *
 * <p>The rules are {@link ResourceAccessGrantService}'s and know nothing about
 * domains; this class is the domain's path into them, the same pass-through the
 * VM and the LLM key have. What the type contributes lives in
 * {@link kr.ac.pusan.pickle.publishing.DomainResourceAdapter}.</p>
 */
@Tag(name = "dns-domain-access",
        description = "도메인 접근 권한 — 이 도메인에 누가 접근할 수 있는지를 정합니다.")
@RestController
@RequestMapping("/api/v1/dns-domains/{domainId}/access")
public class DnsDomainAccessGrantController {

    private final ResourceAccessGrantService service;

    public DnsDomainAccessGrantController(ResourceAccessGrantService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "접근 권한 목록",
            description = "이 도메인의 접근 권한 전체입니다. 도메인 소유자와 워크스페이스 소유자만 볼 수 있습니다.")
    public ResourceAccessListResponse listDnsDomainAccessGrants(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId) {
        return service.list(principal, ResourceType.DOMAIN, domainId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireReauth
    @Operation(summary = "접근 권한 부여",
            description = "지정한 사용자 또는 소유 워크스페이스 전체에 이 도메인의 접근 권한을 부여합니다.")
    public ResourceAccessGrantView addDnsDomainAccessGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId,
            @Valid @RequestBody AddResourceAccessGrantRequest request,
            HttpServletRequest httpRequest) {
        return service.add(principal, ResourceType.DOMAIN, domainId, request,
                clientIp(httpRequest));
    }

    @PatchMapping("/{grantId}")
    @RequireReauth
    @Operation(summary = "접근 권한 등급 변경")
    public ResourceAccessGrantView updateDnsDomainAccessGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId,
            @PathVariable UUID grantId,
            @Valid @RequestBody UpdateResourceAccessGrantRequest request,
            HttpServletRequest httpRequest) {
        return service.update(principal, ResourceType.DOMAIN, domainId, grantId, request,
                clientIp(httpRequest));
    }

    @DeleteMapping("/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireReauth
    @Operation(summary = "접근 권한 회수",
            description = "회수해도 이미 존에 들어간 레코드는 그대로 남습니다. 필요하면 레코드를 먼저 정리해 주세요.")
    public void removeDnsDomainAccessGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId,
            @PathVariable UUID grantId,
            HttpServletRequest httpRequest) {
        service.remove(principal, ResourceType.DOMAIN, domainId, grantId, clientIp(httpRequest));
    }
}
