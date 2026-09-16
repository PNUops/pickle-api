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
    private final DomainIssuancePolicy issuancePolicy;
    private final kr.ac.pusan.pickle.publishing.dns.DnsRecordProvider dnsRecordProvider;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final AuditService auditService;

    public DnsDomainService(DomainRepository domainRepository, DnsDomainQueryService queryService,
            DomainRecordsService recordsService, DomainRenewalPolicy renewalPolicy,
            SubdomainPolicy subdomainPolicy, PublishingService publishingService,
            ResourceAccessGrantRepository grantRepository,
            ResourceAccessResolver resourceAccessResolver,
            DomainIssuancePolicy issuancePolicy,
            kr.ac.pusan.pickle.publishing.dns.DnsRecordProvider dnsRecordProvider,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository, AuditService auditService) {
        this.domainRepository = domainRepository;
        this.queryService = queryService;
        this.recordsService = recordsService;
        this.renewalPolicy = renewalPolicy;
        this.subdomainPolicy = subdomainPolicy;
        this.publishingService = publishingService;
        this.grantRepository = grantRepository;
        this.resourceAccessResolver = resourceAccessResolver;
        this.issuancePolicy = issuancePolicy;
        this.dnsRecordProvider = dnsRecordProvider;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.auditService = auditService;
    }

    /**
     * Puts a fresh name in the ground, with nothing said about who owns it.
     *
     * <p>Shared by the two callers that can produce one: the approval flow,
     * which materialises an approved request, and the revival path's sibling
     * below. Neither the access grant nor the audit row is written here —
     * both belong to the flow that decided the name should exist, and writing
     * them here as well is how one name ends up with two owners and two
     * stories about how it arrived.</p>
     *
     * <p>Every check runs here rather than only at submission time. A request
     * can sit in a queue for days, and in that time a root can lose its row,
     * an organisation can be disabled, the reserved-word list can grow and
     * somebody else can take the name. Validating once at the door and
     * trusting it at the counter is how an approval creates something the
     * rules no longer allow.</p>
     */
    @Transactional
    public Domain issue(long workspaceId, String rawLabel, String rawRoot) {
        String rootDomain = resolveRoot(rawRoot);
        DomainRoot root = issuancePolicy.requireIssuable(rootDomain);
        String label = validateLabel(rawLabel);
        // Locked before the count, so counting what the workspace holds and
        // adding to it happen under one holder. Two requests at the cap would
        // otherwise both read a number below it and both commit above it.
        workspaceRepository.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> workspaceNotFound());

        String fqdn = label + "." + rootDomain;
        // Any live row for this name refuses this one. The workspace that holds
        // it in reserve is not an exception here the way it used to be: taking
        // a reserved name back is its own act on a row that already exists, and
        // routing it through issuance would make recovery wait behind whatever
        // queue issuance waits behind.
        Domain held = domainRepository
                .findFirstByFqdnAndStatusNotForUpdate(fqdn, DomainStatus.REMOVED)
                .orElse(null);
        if (held != null) {
            throw heldBy(held, workspaceId);
        }

        requireRoomInWorkspace(workspaceId);
        if (!dnsRecordProvider.configured()) {
            // The platform path refuses here too. Without it the name is issued
            // and every set it is given stays PENDING with no error on it, so
            // the owner is left with a name that never resolves and nothing
            // saying why.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                    "지금은 도메인을 발급할 수 없습니다",
                    "DNS 제공자가 설정되어 있지 않습니다. 관리자에게 문의해 주세요.");
        }
        try {
            return domainRepository.saveAndFlush(Domain.external(workspaceId,
                    root.getOrgId(), fqdn, rootDomain,
                    renewalPolicy.deadlineFrom(Instant.now())));
        } catch (DataIntegrityViolationException raced) {
            // The locked read above guards rows that exist; two claims of a
            // fresh name still race to insert and the partial unique index is
            // the arbiter. The loser gets the same 409, not a 500 at commit.
            throw fqdnTaken();
        }
    }

    /**
     * Takes back a name this workspace is holding in reserve.
     *
     * <p>A door of its own rather than a branch of issuance, and the reason is
     * the clock. The reservation expires — the sweeper reclaims the name once
     * the grace is up — so a recovery that had to wait in an approval queue
     * could outlive the thing it was recovering, and the owner would watch
     * their own name go to somebody else while their request was pending.
     * Nothing is being handed out here either: the workspace already holds the
     * name and it has been occupying one of their slots the whole time.</p>
     *
     * <p>The records do not come back. Restoring them would republish a site
     * its owner had taken down, and they are the part that can be rewritten in
     * a minute while the name is the part that cannot be had again.</p>
     *
     * <p><b>Membership, not a rung.</b> Every other operation on this kind asks
     * the access list, and this one asks the workspace — because the person who
     * has to be able to recover a name is often the one who has no grant on it:
     * a workspace owner whose member issued the name and then left. Requiring a
     * grant would mean granting yourself access to a released row before you
     * could take it back, which is a step nobody would find.</p>
     */
    @Transactional
    public DnsDomainView revive(AuthenticatedUser actor, UUID domainId, String ip) {
        Domain unlocked = queryService.requireExternal(domainId);
        // Re-read under the row lock. Two people pressing 되살리기 on the same
        // name is the ordinary case — the workspace owner and the issuer both
        // looking at the same list — and without the lock both see a released
        // row and both write a fresh deadline.
        Domain held = domainRepository.findByIdForUpdate(unlocked.getId())
                .orElseThrow(() -> DomainResourceAdapter.MESSAGES.notFound());
        Workspace workspace = requireMembership(actor, held);
        if (held.getReleasedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                    "해제한 이름이 아닙니다", "이 도메인은 지금 쓰이고 있습니다.");
        }
        // The root has to still be one we issue under. A name reserved beneath
        // a root that was withdrawn meanwhile cannot come back to life, and
        // saying so here is better than reviving it into a root that no longer
        // answers for it.
        issuancePolicy.requireIssuable(held.getRootDomain());
        held.setReleasedAt(null);
        held.setRenewDueAt(renewalPolicy.deadlineFrom(Instant.now()));
        // The original owner's grant survived the release, but the person
        // taking the name back may not be that person — a workspace owner
        // recovering a name whose issuer has left holds no grant on it. Without
        // this they revive the row and are then refused the view of it.
        if (grantRepository.findByResourceTypeAndResourceIdAndUserId(ResourceType.DOMAIN,
                held.getId(), actor.id()).isEmpty()) {
            grantRepository.save(ResourceAccessGrant.forUser(ResourceType.DOMAIN, held.getId(),
                    actor.id(), ResourceRole.OWNER));
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DNS_DOMAIN_CREATE, "dns_domain", held.getPublicId(),
                java.util.Map.of("fqdn", held.getFqdn(), "revived", true), ip);
        return queryService.get(actor, held.getPublicId());
    }

    /**
     * Lets the name go. The records come down and the name stays reserved for
     * this workspace through the ordinary grace, so a release by mistake is
     * recoverable and the name does not change hands the moment it is dropped.
     */
    @Transactional
    public void delete(AuthenticatedUser actor, UUID domainId, String ip) {
        // Deletion is a standing right rather than a granted one, the way it is
        // for every other kind: the owner of the workspace can release a name
        // whose own owner has left, without first granting themselves access to
        // it and leaving a break-glass entry for doing so.
        Domain domain = requireManager(actor, domainId);
        requireStillHeld(domain);
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
        Domain domain = requireRung(actor, domainId, ResourceRole.EDITOR, true);
        requireStillHeld(domain);
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
        Domain domain = requireRung(actor, domainId, ResourceRole.EDITOR, true);
        // Asked here rather than left to the records service, whose own guard
        // is an invariant check that answers 500: a name its owner has already
        // let go of is a state a request can legitimately arrive in, and the
        // answer to it is a conflict.
        requireStillHeld(domain);
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
     *
     * <p>{@code forWrite} takes the row lock. Every writer needs it and for the
     * same reason: the sweeps that lapse and reclaim a name take it, and a
     * writer that does not read its snapshot before they run and writes it back
     * after — Hibernate's flush writes every column of the entity, so a renewal
     * that loaded a live row would put {@code released_at} back to null over a
     * lapse that had already committed, leaving a row that reads renewed while
     * the push queued by that lapse empties its zone.</p>
     */
    private Domain requireRung(AuthenticatedUser actor, UUID domainId, ResourceRole required) {
        return requireRung(actor, domainId, required, false);
    }

    /**
     * The row, for somebody who may decide what happens to it: its own owner,
     * or an owner of the workspace that holds it. The second is a standing
     * right — it does not come from the access list and is not withdrawn by
     * editing one, which is what keeps a name recoverable after the person who
     * issued it is gone.
     */
    private Domain requireManager(AuthenticatedUser actor, UUID domainId) {
        Domain domain = queryService.requireExternal(domainId);
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.DOMAIN,
                domain.getId(), domain.getWorkspaceId(), actor.id());
        if (!standing.manages()) {
            standing.requireVisible(DomainResourceAdapter.MESSAGES);
            throw DomainResourceAdapter.MESSAGES.notGrantManager();
        }
        return domainRepository.findByIdForUpdate(domain.getId())
                .orElseThrow(() -> DomainResourceAdapter.MESSAGES.notFound());
    }

    private Domain requireRung(AuthenticatedUser actor, UUID domainId, ResourceRole required,
            boolean forWrite) {
        Domain domain = queryService.requireExternal(domainId);
        if (forWrite) {
            domain = domainRepository.findByIdForUpdate(domain.getId())
                    .orElseThrow(() -> DomainResourceAdapter.MESSAGES.notFound());
        }
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.DOMAIN,
                domain.getId(), domain.getWorkspaceId(), actor.id());
        // Invisible is 404 and visible-but-ungranted is an open 403; both are
        // the access machinery's own words.
        standing.requireVisible(DomainResourceAdapter.MESSAGES);
        if (!standing.atLeast(required)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
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
    /**
     * Refuses a name its owner has already let go of, or one that has been
     * reclaimed.
     *
     * <p>A released row is on its way out and a REMOVED one is gone; neither is
     * something to renew, edit or release again. Releasing twice is the one
     * worth naming: without this the second release re-stamps the release time
     * and the grace starts over, so a name could be held out of the shared
     * space for ever by deleting it once a month.</p>
     */
    private static void requireStillHeld(Domain domain) {
        if (domain.getStatus() == DomainStatus.REMOVED) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "해당 도메인이 존재하지 않습니다", "이미 회수된 이름입니다.");
        }
        if (domain.getReleasedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                    "이미 해제한 도메인입니다",
                    "해제한 이름에는 더 이상 손댈 수 없습니다. 예약 기간 동안 같은 이름으로 다시 만들 수 있습니다.");
        }
    }

    /** The workspace a held name belongs to, refusing anyone outside it. */
    private Workspace requireMembership(AuthenticatedUser actor, Domain domain) {
        Workspace workspace = workspaceRepository
                .findByIdAndDeletedAtIsNull(domain.getWorkspaceId())
                .orElseThrow(DnsDomainService::workspaceNotFound);
        if (workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), actor.id())
                .isEmpty()) {
            // The same 404 the name's own machinery gives somebody it is hidden
            // from: whether this name exists is not a non-member's to learn.
            throw DomainResourceAdapter.MESSAGES.notFound();
        }
        return workspace;
    }

    private static ApiException workspaceNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "워크스페이스를 찾을 수 없습니다", "해당 워크스페이스가 존재하지 않습니다.");
    }

    private String resolveRoot(String requested) {
        String rootDomain = requested == null || requested.isBlank()
                ? subdomainPolicy.defaultRootDomain()
                : requested.strip().toLowerCase(Locale.ROOT);
        if (rootDomain == null || rootDomain.isBlank()) {
            // Nothing is configured, which is an operator's problem rather than
            // a mistake in a field the caller did not send.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                    "발급할 수 있는 루트 도메인이 없습니다",
                    "허용된 루트 도메인이 설정되어 있지 않습니다. 관리자에게 문의해 주세요.");
        }
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
                            + "개까지 가질 수 있습니다. 해제한 이름도 예약 기간 동안은 자리를 차지하므로, "
                            + "예약이 끝나기를 기다리거나 쓰고 있는 이름을 정리해 주세요.");
        }
    }

    /**
     * The refusal for a name that is already in the ground.
     *
     * <p>Split on who holds it, and only for the workspace doing the asking.
     * Their own reserved name is recoverable and they should be sent to the
     * door that recovers it rather than told to pick a different name. For
     * everyone else the answer stays exactly what an unheld collision gives —
     * saying "reserved by somebody else" would tell a stranger who holds a
     * name they cannot see.</p>
     */
    private static ApiException heldBy(Domain held, long askingWorkspaceId) {
        if (held.getReleasedAt() != null && held.getWorkspaceId() == askingWorkspaceId) {
            return new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_FQDN_TAKEN,
                    "이 워크스페이스가 예약 중인 이름입니다",
                    "해제한 이름이라 아직 이 워크스페이스의 것입니다. 도메인 목록에서 되살려 주세요.");
        }
        return fqdnTaken();
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
