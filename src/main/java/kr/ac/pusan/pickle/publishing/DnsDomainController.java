package kr.ac.pusan.pickle.publishing;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.publishing.dto.CreateDnsDomainRequest;
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
            description = "내가 속한 워크스페이스의 도메인입니다. 접근 권한이 없는 도메인도 "
                    + "이름과 상태, 소유자까지는 보입니다.")
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "도메인 발급",
            description = "루트 도메인 아래 이름 하나를 발급받습니다. 승인은 필요하지 않으며, "
                    + "발급한 사람이 유일한 소유자가 됩니다. 기관은 고른 루트 도메인을 따릅니다.")
    public DnsDomainView createDnsDomain(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateDnsDomainRequest request,
            HttpServletRequest httpRequest) {
        return service.create(principal, request, clientIp(httpRequest));
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
