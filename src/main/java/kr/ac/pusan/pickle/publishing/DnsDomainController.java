package kr.ac.pusan.pickle.publishing;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.publishing.dto.DnsDomainView;
import kr.ac.pusan.pickle.publishing.dto.DnsRecordSetView;
import kr.ac.pusan.pickle.publishing.dto.ReplaceDnsRecordSetsRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.jspecify.annotations.Nullable;

/**
 * Contract tag {@code dns-domain}: names issued on their own, with no VM.
 *
 * <p>A separate root from {@code /domains} rather than a widening of it. Those
 * paths are scoped through the VM a domain publishes, which these have none of;
 * one operationId cannot be scoped to two different access lists, and the
 * scoping suite is what says so.</p>
 */
@Tag(name = "dns-domain",
        description = "도메인 — VM 없이 이름만 발급받아 자기 서버로 연결합니다.")
@RestController
@RequestMapping("/api/v1/dns-domains")
public class DnsDomainController {

    private final DnsDomainService service;
    private final DnsDomainQueryService queryService;

    public DnsDomainController(DnsDomainService service, DnsDomainQueryService queryService) {
        this.service = service;
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "도메인 목록",
            description = "`workspaceId`를 지정하지 않으면 내가 접근 권한을 가진 도메인만 "
                    + "옵니다. 지정하면 그 워크스페이스의 도메인 전부이고, 접근 권한이 없는 "
                    + "도메인도 이름과 상태, 소유자까지는 보입니다.")
    public PageResponse<DnsDomainView> listDnsDomains(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @Nullable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DnsDomainView> result = queryService.listPage(principal, workspaceId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));
        return PageResponse.of(result.getContent(), result);
    }

    @PostMapping("/{domainId}/revive")
    @Operation(summary = "해제한 도메인 되살리기",
            description = "예약 기간 동안 이 워크스페이스가 붙잡고 있는 이름을 되찾습니다. "
                    + "새로 받는 것이 아니라 이미 가진 것을 되살리는 것이므로 승인을 거치지 "
                    + "않습니다. 레코드는 돌아오지 않으며 다시 넣어야 합니다.")
    public DnsDomainView reviveDnsDomain(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId,
            HttpServletRequest httpRequest) {
        return service.revive(principal, domainId, clientIp(httpRequest));
    }

    @GetMapping("/{domainId}")
    @Operation(summary = "도메인 상세")
    public DnsDomainView getDnsDomain(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId) {
        return queryService.get(principal, domainId);
    }

    @DeleteMapping("/{domainId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequireReauth
    @Operation(summary = "도메인 해제",
            description = "레코드를 지우고 이름을 해제합니다. 이름은 예약 기간 동안 이 워크스페이스에 "
                    + "남아 있다가 회수됩니다.")
    public void deleteDnsDomain(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId,
            HttpServletRequest httpRequest) {
        service.delete(principal, domainId, clientIp(httpRequest));
    }

    @PostMapping("/{domainId}/renew")
    @Operation(summary = "사용 연장",
            description = "사용 기한을 지금부터 다시 전체 기간만큼으로 옮깁니다. 남은 기간에 더하는 것이 "
                    + "아니라 언제 눌러도 같은 기간을 받습니다.")
    public DnsDomainView renewDnsDomain(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId,
            HttpServletRequest httpRequest) {
        return service.renew(principal, domainId, clientIp(httpRequest));
    }

    @GetMapping("/{domainId}/records")
    @Operation(summary = "레코드 세트 목록")
    public List<DnsRecordSetView> listDnsRecordSets(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId) {
        return service.listRecords(principal, domainId);
    }

    @PutMapping("/{domainId}/records")
    @Operation(summary = "레코드 세트 저장",
            description = "이 이름이 가져야 할 레코드 세트 전체를 보냅니다. 서버가 차이를 계산하므로 "
                    + "같은 내용을 다시 보내면 아무것도 바뀌지 않습니다. 목록에서 빠진 세트는 존에서 지워집니다.")
    public List<DnsRecordSetView> replaceDnsRecordSets(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID domainId,
            @Valid @RequestBody ReplaceDnsRecordSetsRequest request,
            HttpServletRequest httpRequest) {
        return service.replaceRecords(principal, domainId, request, clientIp(httpRequest));
    }
}
