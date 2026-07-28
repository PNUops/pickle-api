package kr.ac.pusan.pickle.publishing;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.publishing.dto.DomainDetailView;
import kr.ac.pusan.pickle.publishing.dto.DomainSummaryView;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vmrequest.VmRequest;
import kr.ac.pusan.pickle.vmrequest.VmRequestRepository;
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
 * User HTTP publishing (contract tag {@code publishing}): publish/update/
 * unpublish a VM's HTTP service and manage its domains. Endpoints only validate
 * and write intent (domain/route rows + generation); every proxy-agent call and
 * DNS check happens in the enqueued {@link RouteApplyJob} / {@link DomainVerificationJob}.
 *
 * <p>Authorization: mutating ops require the owning group's OWNER/EDITOR (a
 * VIEWER is 403, a non-member 404 — same masking as the power path); reads
 * require VIEWER+. The platform subdomain NAME is chosen by the user — in the
 * publish body or pre-picked on the request form (v0.22.0 self-service; no
 * auto-generated fallback) — and validated here against the full subdomain
 * policy. The routing target IP is never accepted from the client — it is
 * forced server-side to the VM's own allocation in the apply job (SSRF guard).</p>
 */
@Service
public class PublishingService {

    /** RFC 1123 hostname label. */
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final VmRequestRepository requestRepository;
    private final DomainRepository domainRepository;
    private final RouteRepository routeRepository;
    private final CertificateRepository certificateRepository;
    private final RouteGenerations routeGenerations;
    private final PublicationAssembler assembler;
    private final SubdomainPolicy subdomainPolicy;
    private final VmEventRepository vmEventRepository;
    private final AuditService auditService;
    private final JobScheduler jobScheduler;
    private final RouteApplyJob routeApplyJob;
    private final DomainVerificationJob domainVerificationJob;
    private final RateLimitService rateLimitService;
    private final SecureRandom random = new SecureRandom();

    public PublishingService(VmRepository vmRepository, GroupMemberRepository groupMemberRepository,
            VmRequestRepository requestRepository,
            DomainRepository domainRepository, RouteRepository routeRepository,
            CertificateRepository certificateRepository, RouteGenerations routeGenerations,
            PublicationAssembler assembler, SubdomainPolicy subdomainPolicy,
            VmEventRepository vmEventRepository, AuditService auditService, JobScheduler jobScheduler,
            RouteApplyJob routeApplyJob, DomainVerificationJob domainVerificationJob,
            RateLimitService rateLimitService) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.requestRepository = requestRepository;
        this.domainRepository = domainRepository;
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.routeGenerations = routeGenerations;
        this.assembler = assembler;
        this.subdomainPolicy = subdomainPolicy;
        this.vmEventRepository = vmEventRepository;
        this.auditService = auditService;
        this.jobScheduler = jobScheduler;
        this.routeApplyJob = routeApplyJob;
        this.domainVerificationJob = domainVerificationJob;
        this.rateLimitService = rateLimitService;
    }

    // ── publish / update / unpublish ─────────────────────────────────────────

    @Transactional
    public PublicationView publish(AuthenticatedUser actor, long vmId, Integer port,
            String subdomain, String rootDomain, String customDomain, String ip) {
        Vm vm = requireVmOwnerOrEditor(actor, vmId);
        requirePublishableState(vm);
        if (Texts.blankToNull(subdomain) != null && Texts.blankToNull(customDomain) != null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("subdomain",
                    "플랫폼 서브도메인과 커스텀 도메인은 동시에 지정할 수 없습니다.")));
        }
        Domain existing = domainRepository
                .findFirstByVmIdAndStatusNotOrderByIdDesc(vmId, DomainStatus.REMOVED)
                .orElse(null);
        if (existing != null && routeRepository
                .findFirstByDomainIdAndStatusNot(existing.getId(), RouteStatus.REMOVED)
                .isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PUBLICATION_ALREADY_EXISTS,
                    "이미 공개된 VM입니다",
                    "이 VM은 이미 HTTP 서비스가 공개되어 있습니다. 포트·도메인을 바꾸려면 공개 설정을 수정해 주세요.");
        }
        int resolvedPort = validatePort(port);
        String requestedCustom = Texts.blankToNull(customDomain);
        Domain domain;
        if (existing != null) {
            // A domain row without a live route is an unpublish tombstone (a custom
            // row kept for its verification state) — the VM is NOT published
            // (contract: PublicationView.route is required, VmDetail.publication is
            // null when unpublished). Re-publishing the same custom FQDN revives the
            // row (verification preserved); any other target retires it first.
            if (existing.getKind() == DomainKind.CUSTOM && requestedCustom != null
                    && existing.getFqdn().equals(requestedCustom.toLowerCase(Locale.ROOT))) {
                domain = revive(existing, resolvedPort);
            } else {
                retire(existing);
                domain = createPublication(vm, resolvedPort, requestedCustom, subdomain, rootDomain);
            }
        } else {
            domain = createPublication(vm, resolvedPort, requestedCustom, subdomain, rootDomain);
        }
        vmEventRepository.save(new VmEvent(vmId, VmEventType.PUBLISH, actor.id(), domain.getFqdn()));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_PUBLISH,
                "vm", vmId, Map.of("fqdn", domain.getFqdn(), "port", resolvedPort,
                        "kind", domain.getKind().name()), ip);
        return assembler.toPublication(domain);
    }

    @Transactional
    public PublicationView updatePublication(AuthenticatedUser actor, long vmId, Integer port,
            boolean customDomainProvided, String customDomain, String ip) {
        Vm vm = requireVmOwnerOrEditor(actor, vmId);
        if (port == null && !customDomainProvided) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("port",
                    "변경할 포트 또는 커스텀 도메인 중 최소 1개를 지정해야 합니다.")));
        }
        Domain current = domainRepository
                .findFirstByVmIdAndStatusNotOrderByIdDesc(vmId, DomainStatus.REMOVED)
                .orElseThrow(PublishingService::publicationNotFound);
        // An unpublish tombstone (no live route) is not a publication — 404, same
        // as a VM that was never published.
        Route liveRoute = routeRepository
                .findFirstByDomainIdAndStatusNot(current.getId(), RouteStatus.REMOVED)
                .orElseThrow(PublishingService::publicationNotFound);
        requirePublishableState(vm);

        Domain result;
        if (customDomainProvided) {
            // Replace the publication: tear the current one down (custom vhost +
            // cert archived) and create the new target (custom FQDN, or revert to
            // the platform subdomain when customDomain is null).
            String newCustom = Texts.blankToNull(customDomain);
            int resolvedPort = port != null ? validatePort(port) : liveRoute.getTargetPort();
            teardown(current, /* archiveCustomCert */ true);
            // Reverting to the platform subdomain (newCustom == null) reuses the
            // request-form name; PATCH carries no subdomain field — without a
            // stored name the revert answers 422 (unpublish → publish with a name).
            result = createPublication(vm, resolvedPort, newCustom, null, null);
        } else {
            int resolvedPort = validatePort(port);
            result = current;
            Route route = liveRoute;
            route.setTargetPort(resolvedPort);
            route.setGeneration(routeGenerations.next());
            route.setStatus(RouteStatus.PENDING);
            route.setLastError(null);
            Route saved = routeRepository.save(route);
            if (current.getStatus() == DomainStatus.ACTIVE) {
                long routeId = saved.getId();
                enqueueAfterCommit(() -> routeApplyJob.apply(routeId));
            }
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.VM_PUBLICATION_UPDATE, "vm", vmId,
                Map.of("fqdn", result.getFqdn(), "kind", result.getKind().name()), ip);
        return assembler.toPublication(result);
    }

    @Transactional
    public MessageResponse unpublish(AuthenticatedUser actor, long vmId, String ip) {
        requireVmOwnerOrEditor(actor, vmId);
        Domain domain = domainRepository
                .findFirstByVmIdAndStatusNotOrderByIdDesc(vmId, DomainStatus.REMOVED)
                .orElseThrow(PublishingService::publicationNotFound);
        if (routeRepository.findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .isEmpty()) {
            // Tombstone (custom row kept after a previous unpublish): the VM is
            // already unpublished — contract: 404.
            throw publicationNotFound();
        }
        // Unpublish keeps a custom domain's row (verification state preserved),
        // removing only its route; AUTO/REQUESTED rows are cleaned up.
        teardown(domain, /* archiveCustomCert */ false);
        vmEventRepository.save(new VmEvent(vmId, VmEventType.UNPUBLISH, actor.id(), domain.getFqdn()));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.VM_UNPUBLISH,
                "vm", vmId, Map.of("fqdn", domain.getFqdn()), ip);
        return new MessageResponse("HTTP 서비스 공개 해제를 접수했습니다. 잠시 후 외부 접근이 차단됩니다.");
    }

    // ── domains ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<DomainSummaryView> listDomains(AuthenticatedUser actor, Long vmId,
            DomainStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<Domain> result = domainRepository.findForMember(myGroupIds(actor), vmId,
                status != null ? status.name() : null, pageable);
        return PageResponse.of(result.getContent().stream().map(DomainSummaryView::from).toList(), result);
    }

    @Transactional(readOnly = true)
    public DomainDetailView getDomain(AuthenticatedUser actor, long domainId) {
        Domain domain = domainRepository.findById(domainId).orElseThrow(PublishingService::domainNotFound);
        requireVmMember(actor, domain.getVmId());
        return assembler.toDomainDetail(domain);
    }

    @Transactional
    public MessageResponse deleteDomain(AuthenticatedUser actor, long domainId, String ip) {
        Domain domain = domainRepository.findById(domainId).orElseThrow(PublishingService::domainNotFound);
        requireVmOwnerOrEditor(actor, domain.getVmId());
        if (domain.getStatus() == DomainStatus.REMOVED) {
            throw domainNotFound();
        }
        teardown(domain, /* archiveCustomCert */ true);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.DOMAIN_DELETE,
                "domain", domainId, Map.of("fqdn", domain.getFqdn()), ip);
        return new MessageResponse("도메인 삭제를 접수했습니다.");
    }

    @Transactional
    public DomainDetailView verifyDomain(AuthenticatedUser actor, long domainId, String ip) {
        Domain domain = domainRepository.findById(domainId).orElseThrow(PublishingService::domainNotFound);
        requireVmOwnerOrEditor(actor, domain.getVmId());
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

    // ── shared publication core ──────────────────────────────────────────────

    /** Creates the domain + route rows and enqueues apply (platform) or verification (custom). */
    private Domain createPublication(Vm vm, int port, String customDomain,
            String subdomain, String rootDomain) {
        Domain domain;
        boolean applyNow;
        if (customDomain != null) {
            String fqdn = customDomain.toLowerCase(Locale.ROOT);
            validateCustomDomain(fqdn);
            requireFqdnFree(fqdn);
            domain = saveDomainOrFqdnTaken(Domain.custom(vm.getId(), fqdn, generateToken()));
            certificateRepository.save(Certificate.letsEncrypt(domain.getId(), domain.getFqdn()));
            applyNow = false;
        } else {
            domain = saveDomainOrFqdnTaken(platformDomain(vm, subdomain, rootDomain));
            applyNow = true;
        }
        long generation = routeGenerations.next();
        Route route = routeRepository.save(new Route(domain.getId(), port, generation));
        long routeId = route.getId();
        long domainId = domain.getId();
        if (applyNow) {
            enqueueAfterCommit(() -> routeApplyJob.apply(routeId));
        } else {
            runAfterCommit(() -> domainVerificationJob.requestVerify(domainId));
        }
        return domain;
    }

    /**
     * saveAndFlush + catch, scoped to the one statement that can lose the race:
     * {@code requireFqdnFree} is a pre-check only — under a concurrent claim of
     * the same name the partial unique index ({@code domains_fqdn_live_idx}) is
     * the arbiter, and the loser must get the same 409 instead of a 500 at
     * commit time. Nothing else runs inside the catch, so a validation failure
     * or a certificate-insert violation still surfaces as itself.
     */
    private Domain saveDomainOrFqdnTaken(Domain domain) {
        try {
            return domainRepository.saveAndFlush(domain);
        } catch (DataIntegrityViolationException raced) {
            throw fqdnTaken();
        }
    }

    /**
     * Resolves the platform subdomain (v0.22.0 self-service): the publish body's
     * {@code subdomain} wins, else the request-form value; neither ⇒ 422 (no
     * auto-generated fallback). The label runs the full {@link SubdomainPolicy}
     * (pattern/reserved/profanity) — the publish path is the final gate now that
     * approval no longer confirms names.
     */
    private Domain platformDomain(Vm vm, String requestedSubdomain, String requestedRootDomain) {
        VmRequest request = requestRepository.findById(vm.getRequestId()).orElse(null);
        String label = Texts.blankToNull(requestedSubdomain) != null
                ? requestedSubdomain
                : (request != null ? request.getDesiredSubdomain() : null);
        if (label == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("subdomain",
                    "공개할 서브도메인을 입력해 주세요. (자동 생성은 지원하지 않습니다)")));
        }
        // Normalize once for BOTH sources: validateLabel lowercases only its
        // local copy, and the FQDN below must match what was validated (DNS is
        // case-insensitive; the unique index is not).
        label = label.strip().toLowerCase(Locale.ROOT);
        String rootDomain = Texts.blankToNull(requestedRootDomain);
        if (rootDomain == null) {
            rootDomain = request != null ? request.getRootDomain() : null;
            // The stored root may be a submit-time snapshot (possibly just the
            // resolved default of that day) — if the operator has since retired
            // it from the allowed list, fall back to the CURRENT default instead
            // of stranding the request behind a 422 for a field the user never
            // typed. A root supplied in the publish body still hard-fails below.
            if (rootDomain == null || !subdomainPolicy.isAllowedRootDomain(rootDomain)) {
                rootDomain = subdomainPolicy.defaultRootDomain();
            }
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
        String fqdn = label + "." + rootDomain;
        requireFqdnFree(fqdn);
        return Domain.platform(vm.getId(), DomainKind.REQUESTED, fqdn, rootDomain);
    }

    /**
     * Re-publishes onto an unpublish tombstone of the SAME custom FQDN: a fresh
     * route (bumped generation), a live cert row if the old one was revoked, and
     * the apply/verify hand-off per the domain's preserved verification state.
     */
    private Domain revive(Domain domain, int port) {
        if (certificateRepository
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

    /** Retires a tombstone whose FQDN is not being re-published (cert archived). */
    private void retire(Domain domain) {
        domain.setStatus(DomainStatus.REMOVED);
        certificateRepository.findByDomainId(domain.getId())
                .forEach(cert -> cert.setStatus(CertificateStatus.REVOKED));
    }

    /**
     * Removes the live route (ABSENT apply) and cleans up the domain/cert per
     * kind. Package-private: the admin force-release (contract v0.18.0) reuses
     * the exact user-deletion semantics instead of duplicating them.
     */
    void teardown(Domain domain, boolean archiveCustomCert) {
        routeRepository.findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .ifPresent(route -> {
                    route.setStatus(RouteStatus.REMOVED);
                    route.setGeneration(routeGenerations.next());
                    long routeId = route.getId();
                    enqueueAfterCommit(() -> routeApplyJob.apply(routeId));
                });
        if (domain.getKind() == DomainKind.CUSTOM) {
            if (archiveCustomCert) {
                domain.setStatus(DomainStatus.REMOVED);
                certificateRepository.findByDomainId(domain.getId())
                        .forEach(cert -> cert.setStatus(CertificateStatus.REVOKED));
            }
            // else: unpublish keeps the custom domain row for its verification state.
        } else {
            domain.setStatus(DomainStatus.REMOVED);
        }
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

    private void requireFqdnFree(String fqdn) {
        if (domainRepository.existsByFqdnAndStatusNot(fqdn, DomainStatus.REMOVED)) {
            throw fqdnTaken();
        }
    }

    private static ApiException fqdnTaken() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_FQDN_TAKEN,
                "이미 사용 중인 도메인입니다",
                "요청한 도메인이 이미 다른 곳에 연결되어 있습니다. 다른 도메인을 사용해 주세요.");
    }

    // ── authorization helpers ────────────────────────────────────────────────

    private Vm requireVmMember(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(PublishingService::vmNotFound);
        if (groupMemberRepository.findByGroupIdAndUserId(vm.getGroupId(), actor.id()).isEmpty()) {
            throw vmNotFound();
        }
        return vm;
    }

    private Vm requireVmOwnerOrEditor(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(PublishingService::vmNotFound);
        GroupMemberRole role = groupMemberRepository.findByGroupIdAndUserId(vm.getGroupId(), actor.id())
                .map(GroupMember::getRole)
                .orElseThrow(PublishingService::vmNotFound);
        if (role != GroupMemberRole.OWNER && role != GroupMemberRole.EDITOR) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    "HTTP 서비스를 공개할 권한이 없습니다",
                    "그룹 소유자(OWNER) 또는 편집자(EDITOR)만 도메인·포트를 설정할 수 있습니다.");
        }
        return vm;
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

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }

    private static ApiException publicationNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "공개 설정을 찾을 수 없습니다", "이 VM은 공개되어 있지 않습니다. 먼저 HTTP 서비스를 공개해 주세요.");
    }

    private static ApiException domainNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 도메인이 존재하지 않습니다.");
    }
}
