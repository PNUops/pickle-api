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
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.group.GroupRepository;
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
import kr.ac.pusan.pickle.vmrequest.VmRequestReview;
import kr.ac.pusan.pickle.vmrequest.VmRequestReviewRepository;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
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
 * require VIEWER+. The platform subdomain NAME is fixed at approval and never
 * chosen here. The routing target IP is never accepted from the client — it is
 * forced server-side to the VM's own allocation in the apply job (SSRF guard).</p>
 */
@Service
public class PublishingService {

    private static final char[] SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int SUFFIX_LENGTH = 4;
    private static final int MAX_AUTO_ATTEMPTS = 10;
    /** RFC 1123 hostname label. */
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final VmRequestReviewRepository reviewRepository;
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
            GroupRepository groupRepository, VmRequestReviewRepository reviewRepository,
            DomainRepository domainRepository, RouteRepository routeRepository,
            CertificateRepository certificateRepository, RouteGenerations routeGenerations,
            PublicationAssembler assembler, SubdomainPolicy subdomainPolicy,
            VmEventRepository vmEventRepository, AuditService auditService, JobScheduler jobScheduler,
            RouteApplyJob routeApplyJob, DomainVerificationJob domainVerificationJob,
            RateLimitService rateLimitService) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupRepository = groupRepository;
        this.reviewRepository = reviewRepository;
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
            String customDomain, String ip) {
        Vm vm = requireVmOwnerOrEditor(actor, vmId);
        requireHttpGranted(vm);
        requirePublishableState(vm);
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
                domain = createPublication(vm, resolvedPort, requestedCustom);
            }
        } else {
            domain = createPublication(vm, resolvedPort, requestedCustom);
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
            result = createPublication(vm, resolvedPort, newCustom);
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
    private Domain createPublication(Vm vm, int port, String customDomain) {
        Domain domain;
        boolean applyNow;
        if (customDomain != null) {
            String fqdn = customDomain.toLowerCase(Locale.ROOT);
            validateCustomDomain(fqdn);
            requireFqdnFree(fqdn);
            domain = domainRepository.save(Domain.custom(vm.getId(), fqdn, generateToken()));
            certificateRepository.save(Certificate.letsEncrypt(domain.getId(), fqdn));
            applyNow = false;
        } else {
            domain = domainRepository.save(platformDomain(vm));
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

    /** Resolves the granted platform subdomain (REQUESTED) or a fresh AUTO one. */
    private Domain platformDomain(Vm vm) {
        VmRequestReview review = reviewRepository.findByRequestId(vm.getRequestId()).orElse(null);
        String grantedSubdomain = review != null ? review.getGrantedSubdomain() : null;
        String rootDomain = review != null && review.getGrantedRootDomain() != null
                ? review.getGrantedRootDomain() : subdomainPolicy.defaultRootDomain();
        if (rootDomain == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "공개할 수 없습니다", "허용된 루트 도메인이 설정되어 있지 않습니다. 관리자에게 문의해 주세요.");
        }
        if (grantedSubdomain != null) {
            String fqdn = grantedSubdomain + "." + rootDomain;
            requireFqdnFree(fqdn);
            return Domain.platform(vm.getId(), DomainKind.REQUESTED, fqdn, rootDomain);
        }
        return Domain.platform(vm.getId(), DomainKind.AUTO, generateAutoFqdn(vm.getGroupId(), rootDomain),
                rootDomain);
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

    /** Removes the live route (ABSENT apply) and cleans up the domain/cert per kind. */
    private void teardown(Domain domain, boolean archiveCustomCert) {
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

    private String generateAutoFqdn(long groupId, String rootDomain) {
        String slug = groupRepository.findById(groupId).map(Group::getSlug).orElse("vm");
        for (int attempt = 0; attempt < MAX_AUTO_ATTEMPTS; attempt++) {
            StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
            for (int i = 0; i < SUFFIX_LENGTH; i++) {
                suffix.append(SUFFIX_ALPHABET[random.nextInt(SUFFIX_ALPHABET.length)]);
            }
            String fqdn = slug + "-" + suffix + "." + rootDomain;
            if (!domainRepository.existsByFqdnAndStatusNot(fqdn, DomainStatus.REMOVED)) {
                return fqdn;
            }
        }
        throw new IllegalStateException("Could not generate a unique auto subdomain for group " + groupId);
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
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_FQDN_TAKEN,
                    "이미 사용 중인 도메인입니다",
                    "요청한 도메인이 이미 다른 곳에 연결되어 있습니다. 다른 도메인을 사용해 주세요.");
        }
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

    private void requireHttpGranted(Vm vm) {
        boolean granted = reviewRepository.findByRequestId(vm.getRequestId())
                .map(VmRequestReview::getGrantHttp)
                .orElse(false) == Boolean.TRUE;
        if (!granted) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.VM_HTTP_NOT_GRANTED,
                    "HTTP 공개가 허용되지 않은 VM입니다",
                    "이 VM은 승인 시 HTTP 공개가 허용되지 않았습니다. 재신청이 필요합니다.");
        }
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
