package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.publishing.DomainRecordPolicy.DesiredSet;
import kr.ac.pusan.pickle.publishing.dto.CreateDnsDomainRequest;
import kr.ac.pusan.pickle.publishing.dto.DnsDomainView;
import kr.ac.pusan.pickle.publishing.dto.DnsRecordSetView;
import kr.ac.pusan.pickle.publishing.dto.ReplaceDnsRecordSetsRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuing a name on its own, letting it go, and keeping it.
 *
 * <p>No approval anywhere in here. That is the decision this kind exists to
 * carry: a name costs the platform a row and a zone entry, and making a person
 * wait for a human to agree to that buys nothing. What stands in for approval
 * is the reserved-label list, the per-workspace cap, and the renewal deadline
 * that takes an unwanted name back.</p>
 */
@Service
public class DnsDomainService {

    /** How many names one workspace may hold at once, before the setting exists. */
    static final int DEFAULT_DOMAINS_PER_WORKSPACE = 5;

    private final DomainRepository domainRepository;
    private final DnsDomainQueryService queryService;
    private final DomainRecordsService recordsService;
    private final DomainRenewalPolicy renewalPolicy;
    private final SubdomainPolicy subdomainPolicy;
    private final PublishingService publishingService;
    private final ResourceAccessGrantRepository grantRepository;
    private final ResourceAccessResolver resourceAccessResolver;
    private final DomainRootRepository domainRootRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final AuditService auditService;

    public DnsDomainService(DomainRepository domainRepository, DnsDomainQueryService queryService,
            DomainRecordsService recordsService, DomainRenewalPolicy renewalPolicy,
            SubdomainPolicy subdomainPolicy, PublishingService publishingService,
            ResourceAccessGrantRepository grantRepository,
            ResourceAccessResolver resourceAccessResolver,
            DomainRootRepository domainRootRepository, WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository, AuditService auditService) {
        this.domainRepository = domainRepository;
        this.queryService = queryService;
        this.recordsService = recordsService;
        this.renewalPolicy = renewalPolicy;
        this.subdomainPolicy = subdomainPolicy;
        this.publishingService = publishingService;
        this.grantRepository = grantRepository;
        this.resourceAccessResolver = resourceAccessResolver;
        this.domainRootRepository = domainRootRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.auditService = auditService;
    }

    /**
     * Issues a name to a workspace the requester belongs to.
     *
     * <p>The requester becomes its only owner, exactly as an approved resource
     * does: nothing is open by default, and anyone else arrives through the
     * access list.</p>
     */
    @Transactional
    public DnsDomainView create(AuthenticatedUser actor, CreateDnsDomainRequest request,
            String ip) {
        Workspace workspace = requireMembership(actor, request.workspaceId());
        String rootDomain = resolveRoot(request.rootDomain());
        DomainRoot root = requireIssuableRoot(rootDomain);
        String label = validateLabel(request.label());
        requireRoomInWorkspace(workspace.getId());

        String fqdn = label + "." + rootDomain;
        // A live row for this name is the answer whoever holds it — including a
        // released one still inside its reservation grace, which is being kept
        // for its own workspace and is not this request's to take.
        domainRepository.findFirstByFqdnAndStatusNotForUpdate(fqdn, DomainStatus.REMOVED)
                .ifPresent(held -> {
                    throw fqdnTaken();
                });

        Domain domain;
        try {
            domain = domainRepository.saveAndFlush(Domain.external(workspace.getId(),
                    root.getOrgId(), fqdn, rootDomain,
                    renewalPolicy.deadlineFrom(Instant.now())));
        } catch (DataIntegrityViolationException raced) {
            // The locked read above guards rows that exist; two claims of a
            // fresh name still race to insert and the partial unique index is
            // the arbiter. The loser gets the same 409, not a 500 at commit.
            throw fqdnTaken();
        }
        grantRepository.save(ResourceAccessGrant.forUser(ResourceType.DOMAIN, domain.getId(),
                actor.id(), ResourceRole.OWNER));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DNS_DOMAIN_CREATE, "dns_domain", domain.getPublicId(),
                java.util.Map.of("fqdn", fqdn), ip);
        return queryService.get(actor, domain.getPublicId());
    }

    /**
     * Lets the name go. The records come down and the name stays reserved for
     * this workspace through the ordinary grace, so a release by mistake is
     * recoverable and the name does not change hands the moment it is dropped.
     */
    @Transactional
    public void delete(AuthenticatedUser actor, UUID domainId, String ip) {
        Domain domain = requireRung(actor, domainId, ResourceRole.EDITOR);
        // One door for taking a domain down, whatever its kind: the release
        // path, the renewal lapse and the administrator's takedown all go
        // through it, so the grace this kind gets is decided in one place.
        publishingService.teardown(domain);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DNS_DOMAIN_DELETE, "dns_domain", domain.getPublicId(),
                java.util.Map.of("fqdn", domain.getFqdn()), ip);
    }

    /**
     * Moves the deadline a full period out from now.
     *
     * <p>Not an extension of what is left. The question the deadline asks is
     * whether anybody is still there, and an answer given early is as good as
     * one given late — so pressing it at any time is allowed and always buys
     * the same period.</p>
     */
    @Transactional
    public DnsDomainView renew(AuthenticatedUser actor, UUID domainId, String ip) {
        Domain domain = requireRung(actor, domainId, ResourceRole.EDITOR);
        if (domain.getReleasedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                    "이미 해제한 도메인입니다",
                    "해제한 이름은 연장할 수 없습니다. 예약 기간 안에 같은 이름으로 다시 만들어 주세요.");
        }
        domain.setRenewDueAt(renewalPolicy.deadlineFrom(Instant.now()));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DNS_DOMAIN_RENEW, "dns_domain", domain.getPublicId(),
                java.util.Map.of("fqdn", domain.getFqdn()), ip);
        return queryService.get(actor, domainId);
    }

    /** The sets this name currently claims. */
    @Transactional(readOnly = true)
    public List<DnsRecordSetView> listRecords(AuthenticatedUser actor, UUID domainId) {
        Domain domain = requireRung(actor, domainId, ResourceRole.VIEWER);
        return recordsService.list(domain.getId()).stream()
                .map(DnsDomainService::toView)
                .toList();
    }

    /** Makes the name's sets exactly what the body asks for. */
    @Transactional
    public List<DnsRecordSetView> replaceRecords(AuthenticatedUser actor, UUID domainId,
            ReplaceDnsRecordSetsRequest request, String ip) {
        Domain domain = requireRung(actor, domainId, ResourceRole.EDITOR);
        List<DesiredSet> desired = new ArrayList<>();
        for (ReplaceDnsRecordSetsRequest.DesiredRecordSet set : request.records()) {
            desired.add(new DesiredSet(set.name(), set.type(), set.values(), set.ttl()));
        }
        List<DomainRecord> saved = recordsService.replace(domain, desired);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DNS_DOMAIN_RECORDS_REPLACE, "dns_domain", domain.getPublicId(),
                java.util.Map.of("fqdn", domain.getFqdn(), "sets", desired.size()), ip);
        return saved.stream().map(DnsDomainService::toView).toList();
    }

    // ── internals ───────────────────────────────────────────────────────────

    /**
     * The row, with the actor's rung on it checked. Below the rung the answer
     * is the access machinery's own: invisible is 404, visible but too low is
     * an open 403.
     */
    private Domain requireRung(AuthenticatedUser actor, UUID domainId, ResourceRole required) {
        Domain domain = queryService.requireExternal(domainId);
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.DOMAIN,
                domain.getId(), domain.getWorkspaceId(), actor.id());
        // Invisible is 404 and visible-but-ungranted is an open 403; both are
        // the access machinery's own words.
        standing.requireVisible(DomainResourceAdapter.MESSAGES);
        if (!standing.atLeast(required)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "이 작업을 수행할 권한이 없습니다",
                    "이 도메인에 대해 더 높은 등급이 필요합니다. 자원 소유자에게 요청해 주세요.");
        }
        return domain;
    }

    /**
     * The root row, which is where the name's organisation comes from.
     *
     * <p>Two gates rather than one, and they answer different questions. The
     * setting says whether names may be issued under this root at all, which an
     * administrator flips; the row says what the root is. A root that passes
     * the setting with no row here is refused, because the alternative is a
     * name with no organisation — invisible to every organisation
     * administrator and visible only to a system administrator, which is the
     * wrong shape for the one resource kind whose content this platform does
     * not control.</p>
     */
    private DomainRoot requireIssuableRoot(String rootDomain) {
        return domainRootRepository.findByRootDomain(rootDomain)
                .orElseThrow(() -> ApiException.validationFailed(List.of(
                        new FieldValidationError("rootDomain",
                                "이 루트 도메인으로는 이름을 발급할 수 없습니다."))));
    }

    private Workspace requireMembership(AuthenticatedUser actor, UUID workspaceId) {
        Workspace workspace = workspaceRepository.findByPublicIdAndDeletedAtIsNull(workspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.RESOURCE_NOT_FOUND, "워크스페이스를 찾을 수 없습니다",
                        "해당 워크스페이스가 존재하지 않습니다."));
        if (workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), actor.id())
                .isEmpty()) {
            // The same 404 an unknown id gets: whether a workspace exists is
            // not something a non-member is told.
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "워크스페이스를 찾을 수 없습니다", "해당 워크스페이스가 존재하지 않습니다.");
        }
        return workspace;
    }

    private String resolveRoot(String requested) {
        String rootDomain = requested == null || requested.isBlank()
                ? subdomainPolicy.defaultRootDomain()
                : requested.strip().toLowerCase(Locale.ROOT);
        List<FieldValidationError> errors = new ArrayList<>();
        subdomainPolicy.validateRootDomain(rootDomain, "rootDomain", errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        return rootDomain;
    }

    private String validateLabel(String requested) {
        String label = requested == null ? "" : requested.strip().toLowerCase(Locale.ROOT);
        List<FieldValidationError> errors = new ArrayList<>();
        subdomainPolicy.validateLabel(label, "label", errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        return label;
    }

    /**
     * The cap counts names the workspace is still holding, released ones
     * included: a released name is out of the shared space for its whole grace,
     * so letting it not count would let one workspace hold any number of names
     * by releasing and re-issuing.
     */
    private void requireRoomInWorkspace(long workspaceId) {
        long held = domainRepository.countByWorkspaceIdAndKindAndStatusNot(workspaceId,
                DomainKind.EXTERNAL, DomainStatus.REMOVED);
        if (held >= DEFAULT_DOMAINS_PER_WORKSPACE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DNS_DOMAIN_LIMIT_REACHED,
                    "도메인을 더 만들 수 없습니다",
                    "워크스페이스당 " + DEFAULT_DOMAINS_PER_WORKSPACE
                            + "개까지 가질 수 있습니다. 쓰지 않는 이름을 삭제한 뒤 다시 시도해 주세요.");
        }
    }

    private static ApiException fqdnTaken() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_FQDN_TAKEN,
                "이미 사용 중인 이름입니다",
                "다른 이름을 입력해 주세요. 최근에 해제된 이름은 예약 기간이 끝나야 다시 쓸 수 있습니다.");
    }

    private static DnsRecordSetView toView(DomainRecord record) {
        return new DnsRecordSetView(record.getName(), record.getType(), record.getRrdatas(),
                record.getTtl(), record.getStatus(), record.getLastError(), record.getAppliedAt());
    }
}
