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
import kr.ac.pusan.pickle.user.UserRole;
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

    public AdminPublishingService(RouteRepository routeRepository, DomainRepository domainRepository,
            CertificateRepository certificateRepository, VmRepository vmRepository,
            GroupRepository groupRepository, OrgRepository orgRepository,
            PublicationAssembler assembler, AuditService auditService, JobScheduler jobScheduler,
            ResyncRoutesJob resyncRoutesJob) {
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
            return new AdminRouteView(route.getId(),
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
                        daysUntilExpiry(now, cert.getNotAfter()), cert.getLastError()))
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

    // ── helpers ──────────────────────────────────────────────────────────────

    private Long scopedOrgId(AuthenticatedUser actor, Long orgId) {
        if (actor.role() == UserRole.ORG_ADMIN) {
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
