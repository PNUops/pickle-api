package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.publishing.dto.AdminCertificateView;
import kr.ac.pusan.pickle.publishing.dto.AdminDomainView;
import kr.ac.pusan.pickle.publishing.dto.AdminRouteView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Admin publishing views (contract tag {@code admin}): route/domain/certificate
 * listings and the SYS_ADMIN sync-all trigger. ORG_ADMIN is hard-scoped to its
 * own org; SYS_ADMIN sees all and may filter by {@code orgId}. The shared
 * platform wildcard cert is visible to every admin.
 */
@Service
public class AdminPublishingService {

    private final RouteRepository routeRepository;
    private final DomainRepository domainRepository;
    private final CertificateRepository certificateRepository;
    private final VmRepository vmRepository;
    private final GroupRepository groupRepository;
    private final OrgRepository orgRepository;
    private final PublicationAssembler assembler;
    private final AuditService auditService;
    private final JobScheduler jobScheduler;
    private final ResyncRoutesJob resyncRoutesJob;
    private final PublishingService publishingService;
    private final DomainVerificationJob domainVerificationJob;
    private final RouteGenerations routeGenerations;
    private final RouteApplyJob routeApplyJob;

    public AdminPublishingService(RouteRepository routeRepository, DomainRepository domainRepository,
            CertificateRepository certificateRepository, VmRepository vmRepository,
            GroupRepository groupRepository, OrgRepository orgRepository,
            PublicationAssembler assembler, AuditService auditService, JobScheduler jobScheduler,
            ResyncRoutesJob resyncRoutesJob, PublishingService publishingService,
            DomainVerificationJob domainVerificationJob, RouteGenerations routeGenerations,
            RouteApplyJob routeApplyJob) {
        this.routeRepository = routeRepository;
        this.domainRepository = domainRepository;
        this.certificateRepository = certificateRepository;
        this.vmRepository = vmRepository;
        this.groupRepository = groupRepository;
        this.orgRepository = orgRepository;
        this.assembler = assembler;
        this.auditService = auditService;
        this.jobScheduler = jobScheduler;
        this.resyncRoutesJob = resyncRoutesJob;
        this.publishingService = publishingService;
        this.domainVerificationJob = domainVerificationJob;
        this.routeGenerations = routeGenerations;
        this.routeApplyJob = routeApplyJob;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminRouteView> listRoutes(AuthenticatedUser actor, Long orgId,
            RouteStatus status, int page, int size) {
        Long scopedOrgId = scopedOrgId(actor, orgId);
        Page<Route> routes = routeRepository.findAdmin(scopedOrgId, name(status), page(page, size));
        Context ctx = context(routes.getContent().stream()
                .map(r -> domainRepository.findById(r.getDomainId()).orElse(null))
                .filter(d -> d != null).toList());
        List<AdminRouteView> content = routes.getContent().stream().map(route -> {
            Domain domain = ctx.domains.get(route.getDomainId());
            Vm vm = domain != null ? ctx.vms.get(domain.getVmId()) : null;
            return new AdminRouteView(route.getId(), route.getDomainId(),
                    domain != null ? domain.getFqdn() : null,
                    domain != null ? domain.getKind() : null,
                    vm != null ? vm.getId() : null, name(vm),
                    vm != null ? vm.getGroupId() : null, ctx.groupName(vm),
                    vm != null ? vm.getOrgId() : null, ctx.orgName(vm),
                    route.getTargetPort(), route.getProtocol(), route.getStatus(),
                    route.getAppliedGeneration(), route.getAppliedAt(), route.getLastError(),
                    route.getUpdatedAt());
        }).toList();
        return PageResponse.of(content, routes);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminDomainView> listDomains(AuthenticatedUser actor, Long orgId,
            DomainKind kind, DomainStatus status, int page, int size) {
        Long scopedOrgId = scopedOrgId(actor, orgId);
        Page<Domain> domains = domainRepository.findAdmin(scopedOrgId, name(kind), name(status),
                page(page, size));
        Context ctx = context(domains.getContent());
        List<AdminDomainView> content = domains.getContent().stream().map(domain -> {
            Vm vm = ctx.vms.get(domain.getVmId());
            RouteStatus routeStatus = routeRepository
                    .findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                    .map(Route::getStatus).orElse(null);
            var certStatus = assembler.certificateFor(domain).map(Certificate::getStatus).orElse(null);
            return new AdminDomainView(domain.getId(), domain.getVmId(), domain.getKind(),
                    domain.getFqdn(), domain.getRootDomain(), domain.getStatus(),
                    domain.getVerifiedAt(), domain.getCreatedAt(), name(vm),
                    vm != null ? vm.getGroupId() : null, ctx.groupName(vm),
                    vm != null ? vm.getOrgId() : null, ctx.orgName(vm),
                    routeStatus, certStatus, domain.getUpdatedAt());
        }).toList();
        return PageResponse.of(content, domains);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminCertificateView> listCertificates(AuthenticatedUser actor, Long orgId,
            CertificateStatus status, Integer expiringInDays, int page, int size) {
        Long scopedOrgId = scopedOrgId(actor, orgId);
        Instant now = Instant.now();
        Page<Certificate> certs = expiringInDays != null
                ? certificateRepository.findAdminExpiring(scopedOrgId, name(status),
                        now.plus(expiringInDays, ChronoUnit.DAYS), page(page, size))
                : certificateRepository.findAdmin(scopedOrgId, name(status), page(page, size));
        List<AdminCertificateView> content = certs.getContent().stream()
                .map(cert -> new AdminCertificateView(cert.getId(), cert.getKind(), cert.getStatus(),
                        cert.getScope(), cert.getDomainId(), cert.getNotAfter(),
                        // a FAILED cert has no meaningful expiry countdown
                        cert.getStatus() == CertificateStatus.FAILED ? null
                                : daysUntilExpiry(now, cert.getNotAfter()),
                        cert.getLastError()))
                .toList();
        return PageResponse.of(content, certs);
    }

    @Transactional
    public MessageResponse resync(AuthenticatedUser actor, String ip) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobScheduler.enqueue(resyncRoutesJob::run);
            }
        });
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.ROUTE_RESYNC,
                "route", null, Map.of(), ip);
        return new MessageResponse("라우트 전체 재동기화를 접수했습니다. 잠시 후 적용 상태가 갱신됩니다.");
    }

    // ── post-hoc intervention (contract v0.18.0, all admin roles org-scoped) ──

    /**
     * Immediate admin release of a problem domain — identical semantics to the
     * user-side domain deletion ({@code teardown(domain, true)}): route removal
     * pushed to the proxy, domain REMOVED, custom certs revoked.
     */
    @Transactional
    public MessageResponse forceRelease(AuthenticatedUser actor, long domainId, String ip) {
        Domain domain = requireScopedDomain(actor, domainId);
        publishingService.teardown(domain, /* archiveCustomCert */ true);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DOMAIN_FORCE_RELEASE, "domain", domainId,
                Map.of("fqdn", domain.getFqdn()), ip);
        return new MessageResponse("도메인을 강제 해제했습니다. 라우트 제거가 곧 적용됩니다.");
    }

    /**
     * Forced ownership re-verification of a custom domain. Same trigger as the
     * user op minus the per-user rate limit — the per-domain in-flight dedupe
     * in {@link DomainVerificationJob} still bounds the load.
     */
    @Transactional
    public MessageResponse verify(AuthenticatedUser actor, long domainId, String ip) {
        Domain domain = requireScopedDomain(actor, domainId);
        if (domain.getKind() != DomainKind.CUSTOM) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_CUSTOM,
                    "검증할 수 없는 도메인입니다", "플랫폼 서브도메인은 소유권 검증이 필요하지 않습니다.");
        }
        runAfterCommit(() -> domainVerificationJob.requestVerify(domainId));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DOMAIN_ADMIN_VERIFY, "domain", domainId,
                Map.of("fqdn", domain.getFqdn()), ip);
        return new MessageResponse("소유권 재검증을 접수했습니다. 잠시 후 상태가 갱신됩니다.");
    }

    /**
     * Re-applies a single route's <em>current desired state</em> to the proxy:
     * live routes go back to PENDING with a fresh generation (the agent 409s
     * stale generations, so the bump is mandatory); a REMOVED route re-pushes
     * its removal. Complements the platform-wide {@link #resync} without the
     * authoritative prune. A live push requires the domain to be ACTIVE — an
     * unverified custom domain must never reach the proxy (the same invariant
     * the resync and the unconfirmed-route sweep enforce), so anything else
     * answers 409.
     */
    @Transactional
    public MessageResponse applyRoute(AuthenticatedUser actor, long routeId, String ip) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(AdminPublishingService::routeNotFound);
        Domain domain = domainRepository.findById(route.getDomainId())
                .orElseThrow(AdminPublishingService::routeNotFound);
        requireScope(actor, domain);
        if (route.getStatus() != RouteStatus.REMOVED
                && domain.getStatus() != DomainStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                    "적용할 수 없는 도메인 상태입니다",
                    "소유권 검증이 완료(ACTIVE)된 도메인의 라우트만 재적용할 수 있습니다. (현재 상태 "
                            + domain.getStatus() + ")");
        }
        route.setGeneration(routeGenerations.next());
        if (route.getStatus() != RouteStatus.REMOVED) {
            route.setStatus(RouteStatus.PENDING);
            route.setLastError(null);
        }
        long id = route.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobScheduler.enqueue(() -> routeApplyJob.apply(id));
            }
        });
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.ROUTE_APPLY,
                "route", routeId, Map.of("fqdn", domain.getFqdn()), ip);
        return new MessageResponse("라우트 재적용을 접수했습니다. 잠시 후 적용 상태가 갱신됩니다.");
    }

    /**
     * Target resolution with the admin 404 mask: unknown id, already-REMOVED
     * domain, and an org-tier actor naming another org's domain all answer the
     * same 404.
     */
    private Domain requireScopedDomain(AuthenticatedUser actor, long domainId) {
        Domain domain = domainRepository.findById(domainId)
                .orElseThrow(AdminPublishingService::domainNotFound);
        if (domain.getStatus() == DomainStatus.REMOVED) {
            throw domainNotFound();
        }
        requireScope(actor, domain);
        return domain;
    }

    private void requireScope(AuthenticatedUser actor, Domain domain) {
        if (!actor.role().isOrgTier()) {
            return;
        }
        Long vmOrgId = vmRepository.findById(domain.getVmId()).map(Vm::getOrgId).orElse(null);
        if (vmOrgId == null || !vmOrgId.equals(actor.orgId())) {
            throw domainNotFound();
        }
    }

    private void runAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static ApiException domainNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 도메인이 존재하지 않습니다.");
    }

    private static ApiException routeNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 라우트가 존재하지 않습니다.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Long scopedOrgId(AuthenticatedUser actor, Long orgId) {
        if (actor.role().isOrgTier()) {
            if (actor.orgId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                        "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
            }
            return actor.orgId();
        }
        return orgId;
    }

    private static Pageable page(int page, int size) {
        return PageRequest.of(page, size);
    }

    private static String name(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    private static Integer daysUntilExpiry(Instant now, Instant notAfter) {
        return notAfter == null ? null : (int) ChronoUnit.DAYS.between(now, notAfter);
    }

    private static String name(Vm vm) {
        return vm != null ? vm.getName() : null;
    }

    /** Batch-resolves the VM/group/org context for a set of domains. */
    private Context context(List<Domain> domains) {
        Set<Long> vmIds = domains.stream().map(Domain::getVmId).collect(Collectors.toSet());
        Map<Long, Vm> vms = byId(vmRepository.findAllById(vmIds), Vm::getId);
        Set<Long> groupIds = vms.values().stream().map(Vm::getGroupId).collect(Collectors.toSet());
        Set<Long> orgIds = vms.values().stream().map(Vm::getOrgId).collect(Collectors.toSet());
        Map<Long, Group> groups = byId(groupRepository.findAllById(groupIds), Group::getId);
        Map<Long, Org> orgs = byId(orgRepository.findAllById(orgIds), Org::getId);
        return new Context(byId(domains, Domain::getId), vms, groups, orgs);
    }

    private static <T> Map<Long, T> byId(Iterable<T> entities, Function<T, Long> idOf) {
        Map<Long, T> map = new java.util.HashMap<>();
        entities.forEach(e -> map.put(idOf.apply(e), e));
        return map;
    }

    private record Context(Map<Long, Domain> domains, Map<Long, Vm> vms, Map<Long, Group> groups,
            Map<Long, Org> orgs) {

        String groupName(Vm vm) {
            if (vm == null) {
                return null;
            }
            Group group = groups.get(vm.getGroupId());
            return group != null ? group.getName() : null;
        }

        String orgName(Vm vm) {
            if (vm == null) {
                return null;
            }
            Org org = orgs.get(vm.getOrgId());
            return org != null ? org.getName() : null;
        }
    }
}
