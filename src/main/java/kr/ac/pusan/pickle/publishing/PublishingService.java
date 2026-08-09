package kr.ac.pusan.pickle.publishing;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.publishing.dto.DomainDetailView;
import kr.ac.pusan.pickle.publishing.dto.DomainSummaryView;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * User HTTP publishing (contract tag {@code publishing}): attach domains to a
 * VM's HTTP service and manage them. Endpoints only validate and write intent
 * (domain/route rows + generation); every proxy-agent call and DNS check
 * happens in the enqueued {@link RouteApplyJob} / {@link DomainVerificationJob}.
 *
 * <p>A VM may serve several domains at once (contract v0.29.0): each domain
 * carries its own route — and so its own target port. Platform subdomains are
 * capped per VM ({@code settings.platform_subdomains_per_vm}, serving ones
 * only); custom domains are uncapped but rate-limited per user on creation
 * because Let's Encrypt issuance draws on a shared account quota.</p>
 *
 * <p>Authorization: mutating ops require the owning group's OWNER/EDITOR (a
 * VIEWER is 403, a non-member 404 — same masking as the power path); reads
 * require VIEWER+. The platform subdomain NAME is always chosen by the user in
 * the create body (no request-form fallback, no auto-generated name) and
 * validated here against the full subdomain policy. The routing target IP is
 * never accepted from the client — it is forced server-side to the VM's own
 * allocation in the apply job (SSRF guard).</p>
 */
@Service
public class PublishingService {

    /** RFC 1123 hostname label. */
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final VmAccessService vmAccessService;
    private final DomainRepository domainRepository;
    private final RouteRepository routeRepository;
    private final CertificateRepository certificateRepository;
    private final RouteGenerations routeGenerations;
    private final PublicationAssembler assembler;
    private final SubdomainPolicy subdomainPolicy;
    private final SettingsService settingsService;
    private final VmEventRepository vmEventRepository;
    private final AuditService auditService;
    private final JobScheduler jobScheduler;
    private final RouteApplyJob routeApplyJob;
    private final DomainVerificationJob domainVerificationJob;
    private final RateLimitService rateLimitService;
    private final SecureRandom random = new SecureRandom();

    public PublishingService(VmRepository vmRepository, GroupMemberRepository groupMemberRepository,
            VmAccessService vmAccessService,
            DomainRepository domainRepository, RouteRepository routeRepository,
            CertificateRepository certificateRepository, RouteGenerations routeGenerations,
            PublicationAssembler assembler, SubdomainPolicy subdomainPolicy,
            SettingsService settingsService,
            VmEventRepository vmEventRepository, AuditService auditService, JobScheduler jobScheduler,
            RouteApplyJob routeApplyJob, DomainVerificationJob domainVerificationJob,
            RateLimitService rateLimitService) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.vmAccessService = vmAccessService;
        this.domainRepository = domainRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.routeGenerations = routeGenerations;
        this.assembler = assembler;
        this.subdomainPolicy = subdomainPolicy;
        this.settingsService = settingsService;
        this.vmEventRepository = vmEventRepository;
        this.auditService = auditService;
        this.jobScheduler = jobScheduler;
        this.routeApplyJob = routeApplyJob;
        this.domainVerificationJob = domainVerificationJob;
        this.rateLimitService = rateLimitService;
    }

    // ── create / update / delete a domain ────────────────────────────────────

    @Transactional
    public PublicationView createDomain(AuthenticatedUser actor, long vmId, Integer port,
            String subdomain, String rootDomain, String customDomain, String ip) {
        Vm vm = requireVmOwnerOrEditor(actor, vmId);
        requirePublishableState(vm);
        if (Texts.blankToNull(subdomain) != null && Texts.blankToNull(customDomain) != null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("subdomain",
                    "플랫폼 서브도메인과 커스텀 도메인은 동시에 지정할 수 없습니다.")));
        }
        int resolvedPort = validatePort(port);
        String requestedCustom = Texts.blankToNull(customDomain);
        Domain domain = requestedCustom != null
                ? createCustom(actor, vm, resolvedPort, requestedCustom)
                : createPlatform(vm, resolvedPort, subdomain, rootDomain);
        vmEventRepository.save(new VmEvent(vmId, VmEventType.PUBLISH, actor.id(), domain.getFqdn()));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_PUBLISH,
                "vm", vmId, Map.of("fqdn", domain.getFqdn(), "port", resolvedPort,
                        "kind", domain.getKind().name()), ip);
        return assembler.toPublication(domain);
    }

    @Transactional
    public PublicationView updateDomain(AuthenticatedUser actor, long domainId, Integer port,
            String ip) {
        Domain domain = domainRepository.findById(domainId).orElseThrow(PublishingService::domainNotFound);
        Vm vm = requireVmOwnerOrEditor(actor, domain.getVmId());
        if (domain.getStatus() == DomainStatus.REMOVED) {
            throw domainNotFound();
        }
        requirePublishableState(vm);
        if (port == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("port",
                    "변경할 포트를 입력해 주세요.")));
        }
        int resolvedPort = validatePort(port);
        // A released (reserved) domain has no live route — there is no port to
        // change until the name is re-attached.
        Route route = routeRepository
                .findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .orElseThrow(PublishingService::domainNotServing);
        route.setTargetPort(resolvedPort);
        route.setGeneration(routeGenerations.next());
        route.setStatus(RouteStatus.PENDING);
        route.setLastError(null);
        Route saved = routeRepository.save(route);
        if (domain.getStatus() == DomainStatus.ACTIVE) {
            long routeId = saved.getId();
            enqueueAfterCommit(() -> routeApplyJob.apply(routeId));
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.DOMAIN_UPDATE,
                "domain", domainId, Map.of("fqdn", domain.getFqdn(), "port", resolvedPort), ip);
        return assembler.toPublication(domain);
    }

    /**
     * One endpoint, two outcomes. A domain that is serving (live route) is
     * <em>released</em>: the route goes down, and a platform subdomain keeps
     * its row — with {@code releasedAt} stamped — so the name stays reserved
     * for the grace period (the sweeper reclaims it later). A domain that is
     * NOT serving (a reservation the user wants back now, or a leftover custom
     * row) is removed outright, freeing the name immediately.
     */
    @Transactional
    public MessageResponse deleteDomain(AuthenticatedUser actor, long domainId, String ip) {
        Domain domain = domainRepository.findById(domainId).orElseThrow(PublishingService::domainNotFound);
        requireVmOwnerOrEditor(actor, domain.getVmId());
        if (domain.getStatus() == DomainStatus.REMOVED) {
            throw domainNotFound();
        }
        boolean served = assembler.hasLiveRoute(domain);
        teardown(domain);
        if (served) {
            vmEventRepository.save(new VmEvent(domain.getVmId(), VmEventType.UNPUBLISH, actor.id(),
                    domain.getFqdn()));
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.DOMAIN_DELETE,
                "domain", domainId, Map.of("fqdn", domain.getFqdn()), ip);
        return domain.getReleasedAt() != null && domain.getStatus() != DomainStatus.REMOVED
                ? new MessageResponse("도메인 해제를 접수했습니다. 이름은 유예 기간 동안 이 VM에 예약됩니다.")
                : new MessageResponse("도메인 삭제를 접수했습니다.");
    }

    // ── domain reads ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<DomainSummaryView> listDomains(AuthenticatedUser actor, Long vmId,
            DomainStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<Domain> result = domainRepository.findForMember(myGroupIds(actor), vmId,
                status != null ? status.name() : null, pageable);
        return PageResponse.of(result.getContent().stream().map(assembler::toDomainSummary).toList(),
                result);
    }

    @Transactional(readOnly = true)
    public DomainDetailView getDomain(AuthenticatedUser actor, long domainId) {
        Domain domain = domainRepository.findById(domainId).orElseThrow(PublishingService::domainNotFound);
        requireVmMember(actor, domain.getVmId());
        return assembler.toDomainDetail(domain);
    }

    @Transactional
    public DomainDetailView verifyDomain(AuthenticatedUser actor, long domainId, String ip) {
        Domain domain = domainRepository.findById(domainId).orElseThrow(PublishingService::domainNotFound);
        requireVmOwnerOrEditor(actor, domain.getVmId());
        // Same 404 mask as update/delete: a REMOVED row is gone to its owner.
        if (domain.getStatus() == DomainStatus.REMOVED) {
            throw domainNotFound();
        }
        if (domain.getKind() != DomainKind.CUSTOM) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_CUSTOM,
                    "검증할 수 없는 도메인입니다", "플랫폼 서브도메인은 소유권 검증이 필요하지 않습니다.");
        }
        // Each trigger enqueues a (slow) DNS job on the shared JobRunr pool:
        // per-user rate limit + per-domain in-flight dedupe bound the abuse.
        rateLimitService.hit("domain_verify", "user:" + actor.id(),
                RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        runAfterCommit(() -> domainVerificationJob.requestVerify(domainId));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.DOMAIN_VERIFY,
                "domain", domainId, Map.of("fqdn", domain.getFqdn()), ip);
        return assembler.toDomainDetail(domain);
    }

    // ── shared creation core ─────────────────────────────────────────────────

    /** Custom-domain creation: validate, revive a same-VM leftover, or insert fresh. */
    private Domain createCustom(AuthenticatedUser actor, Vm vm, int port, String customDomain) {
        String fqdn = customDomain.toLowerCase(Locale.ROOT);
        validateCustomDomain(fqdn);
        // Every accepted custom domain ends in a Let's Encrypt issuance attempt
        // on the platform's SHARED ACME account — a per-user creation limit
        // keeps one user from exhausting that quota for everyone. Platform
        // subdomains need none of this: one wildcard covers every name under a
        // root, so they issue nothing no matter how many are added.
        //
        // Two windows, because the per-minute one alone does not bound the hour:
        // it caps a burst, but a user pacing themselves within it still reaches
        // the CA's account-wide new-order limit in a few hours and blocks
        // issuance for everybody else. The hourly cap is the one measured
        // against that quota and is an operator setting for the same reason —
        // the CA's limits are not ours to predict.
        rateLimitService.hit("domain_create", "user:" + actor.id(),
                RateLimitService.DEFAULT_LIMIT_PER_MINUTE);
        rateLimitService.hitHourly("domain_create_hourly", "user:" + actor.id(),
                settingsService.integer(SettingsService.CUSTOM_DOMAIN_LIMIT_PER_HOUR, 20));
        // Locked read: the sweeper may reclaim a leftover row concurrently, and
        // a revive must never proceed on a snapshot it already flipped REMOVED.
        Domain existing = domainRepository
                .findFirstByFqdnAndStatusNotForUpdate(fqdn, DomainStatus.REMOVED)
                .orElse(null);
        if (existing != null) {
            requireRevivable(existing, vm);
            return revive(existing, port);
        }
        Domain domain = saveDomainOrFqdnTaken(Domain.custom(vm.getId(), fqdn, generateToken()));
        certificateRepository.save(Certificate.letsEncrypt(domain.getId(), domain.getFqdn()));
        attachRoute(domain, port);
        return domain;
    }

    /** Platform-subdomain creation: resolve the name, enforce the cap, revive or insert. */
    private Domain createPlatform(Vm vm, int port, String subdomain, String rootDomain) {
        PlatformName name = resolvePlatformName(subdomain, rootDomain);
        // Locked read: a revive races the reservation sweeper's reclaim of the
        // same row at the grace boundary ("first commit wins" on its side, the
        // domain row lock on both). An unlocked snapshot here could revive a
        // row the sweep already flipped REMOVED — the user would get a success
        // response for a domain that stays dead. Under the lock a reclaimed row
        // drops out of the predicate and the create proceeds as a fresh insert.
        Domain existing = domainRepository
                .findFirstByFqdnAndStatusNotForUpdate(name.fqdn(), DomainStatus.REMOVED)
                .orElse(null);
        if (existing != null) {
            requireRevivable(existing, vm);
            requirePlatformSlotFree(vm.getId());
            return revive(existing, port);
        }
        requirePlatformSlotFree(vm.getId());
        Domain domain = saveDomainOrFqdnTaken(
                Domain.platform(vm.getId(), DomainKind.PLATFORM, name.fqdn(), name.rootDomain()));
        attachRoute(domain, port);
        return domain;
    }

    /**
     * An existing live row for the requested FQDN is only revivable by the VM
     * that already owns it, and only while it is not serving — anything else is
     * the same 409 the unique index would give.
     */
    private void requireRevivable(Domain existing, Vm vm) {
        if (!existing.getVmId().equals(vm.getId()) || assembler.hasLiveRoute(existing)) {
            throw fqdnTaken();
        }
    }

    /** Creates the domain's route and enqueues apply (ACTIVE) or verification. */
    private void attachRoute(Domain domain, int port) {
        long generation = routeGenerations.next();
        Route route = routeRepository.save(new Route(domain.getId(), port, generation));
        long routeId = route.getId();
        long domainId = domain.getId();
        if (domain.getStatus() == DomainStatus.ACTIVE) {
            enqueueAfterCommit(() -> routeApplyJob.apply(routeId));
        } else {
            runAfterCommit(() -> domainVerificationJob.requestVerify(domainId));
        }
    }

    /**
     * saveAndFlush + catch, scoped to the one statement that can lose the race:
     * the locked FQDN read guards only rows that exist — when no row holds the
     * name there is nothing to lock, so two concurrent claims of a fresh name
     * still race to insert and the partial unique index
     * ({@code domains_fqdn_live_idx}) is the arbiter; the loser must get
     * the same 409 instead of a 500 at commit time. Nothing else runs inside
     * the catch, so a validation failure or a certificate-insert violation
     * still surfaces as itself.
     */
    private Domain saveDomainOrFqdnTaken(Domain domain) {
        try {
            return domainRepository.saveAndFlush(domain);
        } catch (DataIntegrityViolationException raced) {
            throw fqdnTaken();
        }
    }

    /**
     * Resolves the platform FQDN from the request body alone: {@code subdomain}
     * is mandatory (no request-form fallback, no auto-generated name — the
     * request form stopped carrying a domain axis in v0.29.0) and
     * {@code rootDomain} defaults to the platform default root. The label runs
     * the full {@link SubdomainPolicy} (pattern/reserved/profanity) — this is
     * the only gate.
     */
    private PlatformName resolvePlatformName(String requestedSubdomain, String requestedRootDomain) {
        String label = Texts.blankToNull(requestedSubdomain);
        if (label == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("subdomain",
                    "공개할 서브도메인을 입력해 주세요. (자동 생성은 지원하지 않습니다)")));
        }
        // Normalize before validating: validateLabel lowercases only its local
        // copy, and the FQDN below must match what was validated (DNS is
        // case-insensitive; the unique index is not).
        label = label.strip().toLowerCase(Locale.ROOT);
        String rootDomain = Texts.blankToNull(requestedRootDomain);
        if (rootDomain == null) {
            rootDomain = subdomainPolicy.defaultRootDomain();
        }
        if (rootDomain == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "공개할 수 없습니다", "허용된 루트 도메인이 설정되어 있지 않습니다. 관리자에게 문의해 주세요.");
        }
        List<FieldValidationError> errors = new ArrayList<>();
        subdomainPolicy.validateLabel(label, "subdomain", errors);
        subdomainPolicy.validateRootDomain(rootDomain, "rootDomain", errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        requireWildcardCertificate(rootDomain);
        return new PlatformName(label + "." + rootDomain, rootDomain);
    }

    private record PlatformName(String fqdn, String rootDomain) {
    }

    /**
     * The per-VM cap counts SERVING platform subdomains only ({@code
     * releasedAt} null): a released name in its grace period must not occupy a
     * slot, or releasing a domain would lock its owner out of creating the
     * replacement for the whole grace period.
     *
     * <p>Counted under the VM row lock: count-then-insert alone lets two
     * concurrent creates both count below the cap and overshoot it by one.
     * The lock serializes platform creates per VM for the rest of this (short,
     * network-free) transaction; unrelated VMs never contend.</p>
     */
    private void requirePlatformSlotFree(long vmId) {
        vmRepository.findByIdForUpdate(vmId).orElseThrow(VmAccessService::vmNotFound);
        int limit = settingsService.integer(SettingsService.PLATFORM_SUBDOMAINS_PER_VM,
                SubdomainPolicy.DEFAULT_SUBDOMAINS_PER_VM);
        long serving = domainRepository.countByVmIdAndKindNotAndStatusNotAndReleasedAtIsNull(
                vmId, DomainKind.CUSTOM, DomainStatus.REMOVED);
        if (serving >= limit) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_LIMIT_REACHED,
                    "플랫폼 서브도메인 개수 제한에 도달했습니다",
                    "이 VM에는 플랫폼 서브도메인을 최대 " + limit + "개까지 연결할 수 있습니다. "
                            + "기존 서브도메인을 해제하거나 커스텀 도메인을 사용해 주세요.");
        }
    }

    /**
     * A platform root is only publishable while a live wildcard certificate is
     * recorded for it. Adding a root is a four-step change (allow-list, certificate
     * on the proxy, agent configuration, certificate row); without this check a root
     * added to the allow-list alone would accept publishes whose routes then fail at
     * the agent, leaving the user with a domain that resolves to nothing and an error
     * only an operator can read.
     */
    private void requireWildcardCertificate(String rootDomain) {
        boolean usable = certificateRepository
                .findLiveWildcard(CertificateKind.ORIGIN_CA_WILDCARD,
                        PublicationAssembler.wildcardScope(rootDomain))
                .filter(cert -> cert.getStatus() == CertificateStatus.ACTIVE)
                .isPresent();
        if (!usable) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "공개할 수 없습니다",
                    "'" + rootDomain + "' 루트 도메인에 사용할 수 있는 인증서가 없습니다. 관리자에게 문의해 주세요.");
        }
    }

    /**
     * Re-attaches the SAME FQDN to the VM that still holds its row: a released
     * platform subdomain comes back into service ({@code releasedAt} cleared —
     * the reservation did its job), a leftover custom row keeps its preserved
     * verification state. Fresh route (bumped generation), a live cert row if
     * the old one was revoked, and the apply/verify hand-off per the domain's
     * verification state.
     */
    private Domain revive(Domain domain, int port) {
        domain.setReleasedAt(null);
        if (domain.getKind() == DomainKind.CUSTOM && certificateRepository
                .findFirstByDomainIdAndStatusNot(domain.getId(), CertificateStatus.REVOKED)
                .isEmpty()) {
            certificateRepository.save(Certificate.letsEncrypt(domain.getId(), domain.getFqdn()));
        }
        Route route = routeRepository.findFirstByDomainId(domain.getId())
                .orElseGet(() -> new Route(domain.getId(), port, routeGenerations.next()));
        route.setTargetPort(port);
        route.setGeneration(routeGenerations.next());
        route.setStatus(RouteStatus.PENDING);
        route.setLastError(null);
        long routeId = routeRepository.save(route).getId();
        long domainId = domain.getId();
        if (domain.getStatus() == DomainStatus.ACTIVE) {
            enqueueAfterCommit(() -> routeApplyJob.apply(routeId));
        } else {
            runAfterCommit(() -> domainVerificationJob.requestVerify(domainId));
        }
        return domain;
    }

    /**
     * Takes a domain down. The live route (if any) flips to REMOVED and its
     * ABSENT state is pushed to the proxy. What happens to the row then depends
     * on whose name space it lives in:
     *
     * <ul>
     * <li><b>Platform subdomain that was serving</b> — the name space is ours,
     * the names are short and contested, and a handover risks routing traffic
     * meant for the previous holder to someone else's service. The row survives
     * with {@code releasedAt} stamped, so the fqdn unique index keeps the name
     * reserved for this VM until the sweeper reclaims it after the grace
     * period.</li>
     * <li><b>Custom domain, or a row not serving at all</b> — removed outright
     * (REMOVED + certs revoked). A custom domain is the user's own DNS: if
     * another account can pass the TXT challenge, control of the name really
     * moved, and holding the row here would only block the new owner. A
     * non-serving row is a reservation being handed back early.</li>
     * </ul>
     *
     * <p>Package-private: the admin force-release reuses the exact
     * user-deletion semantics instead of duplicating them.</p>
     */
    void teardown(Domain domain) {
        Route live = routeRepository
                .findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .orElse(null);
        if (live != null) {
            live.setStatus(RouteStatus.REMOVED);
            live.setGeneration(routeGenerations.next());
            long routeId = live.getId();
            enqueueAfterCommit(() -> routeApplyJob.apply(routeId));
        }
        if (live != null && domain.getKind() != DomainKind.CUSTOM) {
            domain.setReleasedAt(Instant.now());
        } else {
            retire(domain);
        }
    }

    /**
     * Takes a domain down and frees its name in one step, skipping the grace a
     * user's own release gets. An admin reaches for this to stop a name that is
     * causing harm; leaving it reserved would keep the row alive, and a reserved
     * row is exactly what the owner may revive — the takedown would undo itself
     * the moment they re-added the same name.
     */
    void forceTeardown(Domain domain) {
        teardown(domain);
        retire(domain);
    }

    /**
     * Removes the row outright: REMOVED (name freed) + certs revoked. The
     * release stamp goes with the claim — {@code releasedAt} is what marks a
     * row as holding its name in reserve, and a REMOVED row reserves nothing.
     * Left in place it would keep reading as "reserved" (that is the console's
     * discriminator) long after the name was freed.
     */
    private void retire(Domain domain) {
        domain.setStatus(DomainStatus.REMOVED);
        domain.setReleasedAt(null);
        certificateRepository.findByDomainId(domain.getId())
                .forEach(cert -> cert.setStatus(CertificateStatus.REVOKED));
    }

    private String generateToken() {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        return "pv-" + HexFormat.of().formatHex(bytes);
    }

    // ── validation helpers ─────────────────────────────────────────────────

    private int validatePort(Integer port) {
        int value = port != null ? port : 80;
        List<FieldValidationError> errors = new ArrayList<>();
        if (value < 1 || value > 65535) {
            errors.add(new FieldValidationError("port", "포트는 1~65535 범위여야 합니다."));
        } else if (value == 22) {
            errors.add(new FieldValidationError("port", "VM의 SSH 포트(22)는 공개할 수 없습니다."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
        return value;
    }

    private void validateCustomDomain(String fqdn) {
        List<FieldValidationError> errors = new ArrayList<>();
        String[] labels = fqdn.split("\\.", -1);
        boolean validLabels = labels.length >= 2
                && java.util.Arrays.stream(labels).allMatch(l -> LABEL.matcher(l).matches());
        if (!validLabels) {
            errors.add(new FieldValidationError("customDomain",
                    "완전한 외부 도메인(FQDN)이어야 하며 단일 라벨은 사용할 수 없습니다."));
        } else if (subdomainPolicy.isUnderPlatformRoot(fqdn)) {
            errors.add(new FieldValidationError("customDomain",
                    "플랫폼이 관리하는 도메인 하위 값은 커스텀 도메인으로 사용할 수 없습니다."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    private static ApiException fqdnTaken() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_FQDN_TAKEN,
                "이미 사용 중인 도메인입니다",
                "요청한 도메인이 이미 다른 곳에 연결되어 있습니다. 다른 도메인을 사용해 주세요.");
    }

    // ── authorization helpers ────────────────────────────────────────────────

    private Vm requireVmMember(AuthenticatedUser actor, long vmId) {
        return vmAccessService.of(actor, vmId).requireVisible();
    }

    private Vm requireVmOwnerOrEditor(AuthenticatedUser actor, long vmId) {
        return vmAccessService.of(actor, vmId).requireAtLeast(ResourceRole.EDITOR,
                "HTTP 서비스를 공개할 권한이 없습니다",
                "그룹 소유자(OWNER) 또는 편집자(EDITOR)만 도메인·포트를 설정할 수 있습니다.");
    }

    private void requirePublishableState(Vm vm) {
        if (vm.getStatus() != VmStatus.RUNNING && vm.getStatus() != VmStatus.STOPPED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 공개할 수 없습니다",
                    "RUNNING 또는 STOPPED 상태의 VM만 공개할 수 있습니다. (현재 상태 " + vm.getStatus() + ")");
        }
    }

    private List<Long> myGroupIds(AuthenticatedUser actor) {
        List<Long> ids = groupMemberRepository.findWithGroupByUserId(actor.id()).stream()
                .map(m -> m.getGroup().getId())
                .toList();
        return ids.isEmpty() ? List.of(-1L) : ids;
    }

    private void enqueueAfterCommit(JobLambda job) {
        runAfterCommit(() -> jobScheduler.enqueue(job));
    }

    private void runAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static ApiException domainNotServing() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "공개 설정을 찾을 수 없습니다", "이 도메인은 현재 서비스 중이 아닙니다. 도메인을 다시 연결해 주세요.");
    }

    private static ApiException domainNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 도메인이 존재하지 않습니다.");
    }
}
