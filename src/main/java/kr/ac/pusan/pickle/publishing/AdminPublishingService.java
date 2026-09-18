package kr.ac.pusan.pickle.publishing;

import java.util.Collection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.networkpolicy.PublicSourcePolicyService;
import kr.ac.pusan.pickle.networkpolicy.dto.SourcePolicyView;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.publishing.dto.AdminCertificateView;
import kr.ac.pusan.pickle.publishing.dto.AdminDomainView;
import kr.ac.pusan.pickle.publishing.dto.DnsRecordSetView;
import kr.ac.pusan.pickle.publishing.dto.UpdateDomainRenewalRequest;
import kr.ac.pusan.pickle.publishing.dto.AdminRouteView;
import kr.ac.pusan.pickle.orgs.AdminOrgScope;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmActorKind;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventRepository;
import kr.ac.pusan.pickle.vm.VmEventType;
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
 * listings and the SYS_ADMIN sync-all trigger. The org tier reads the
 * organisations it holds a role in and intervenes only in those it operates;
 * the sys tier sees all and may filter by {@code orgId}. The shared platform
 * wildcard cert is visible to every admin.
 */
@Service
public class AdminPublishingService {

    private final RouteRepository routeRepository;
    private final DomainRecordRepository recordRepository;
    private final DomainRepository domainRepository;
    private final CertificateRepository certificateRepository;
    private final VmRepository vmRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final PublicationAssembler assembler;
    private final AuditService auditService;
    private final JobScheduler jobScheduler;
    private final ResyncRoutesJob resyncRoutesJob;
    private final PublishingService publishingService;
    private final DomainVerificationJob domainVerificationJob;
    private final RouteGenerations routeGenerations;
    private final RouteApplyJob routeApplyJob;
    private final VmEventRepository vmEventRepository;
    private final NotificationService notificationService;
    private final PublicSourcePolicyService sourcePolicies;

    public AdminPublishingService(RouteRepository routeRepository, DomainRecordRepository recordRepository, DomainRepository domainRepository,
            CertificateRepository certificateRepository, VmRepository vmRepository,
            WorkspaceRepository workspaceRepository, OrgRepository orgRepository,
            PublicationAssembler assembler, AuditService auditService, JobScheduler jobScheduler,
            ResyncRoutesJob resyncRoutesJob, PublishingService publishingService,
            DomainVerificationJob domainVerificationJob, RouteGenerations routeGenerations,
            RouteApplyJob routeApplyJob, VmEventRepository vmEventRepository,
            NotificationService notificationService, PublicSourcePolicyService sourcePolicies) {
        this.routeRepository = routeRepository;
        this.recordRepository = recordRepository;
        this.domainRepository = domainRepository;
        this.certificateRepository = certificateRepository;
        this.vmRepository = vmRepository;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
        this.assembler = assembler;
        this.auditService = auditService;
        this.jobScheduler = jobScheduler;
        this.resyncRoutesJob = resyncRoutesJob;
        this.publishingService = publishingService;
        this.domainVerificationJob = domainVerificationJob;
        this.routeGenerations = routeGenerations;
        this.routeApplyJob = routeApplyJob;
        this.vmEventRepository = vmEventRepository;
        this.notificationService = notificationService;
        this.sourcePolicies = sourcePolicies;
    }

    @Transactional(readOnly = true)
    public SourcePolicyView getRouteSourcePolicy(AuthenticatedUser actor, UUID routeId) {
        Route route = routeRepository.findByPublicId(routeId)
                .orElseThrow(AdminPublishingService::routeNotFound);
        Domain domain = domainRepository.findById(route.getDomainId())
                .orElseThrow(AdminPublishingService::routeNotFound);
        requireScope(actor, domain, true);
        return sourcePolicies.domainView(domain);
    }

    @Transactional
    public SourcePolicyView updateRouteSourcePolicy(AuthenticatedUser actor, UUID routeId,
            long expectedRevision, List<String> allowedCidrs, String ip) {
        Route route = routeRepository.findByPublicId(routeId)
                .orElseThrow(AdminPublishingService::routeNotFound);
        Domain domain = domainRepository.findById(route.getDomainId())
                .orElseThrow(AdminPublishingService::routeNotFound);
        requireScope(actor, domain);
        return sourcePolicies.updateDomain(domain, actor, expectedRevision, allowedCidrs, true, ip);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminRouteView> listRoutes(AuthenticatedUser actor, UUID orgId,
            RouteStatus status, int page, int size) {
        OrgScope scope = scopedOrgId(actor, orgId);
        Page<Route> routes = routeRepository.findAdmin(orgFilter(scope), name(status), page(page, size));
        Context ctx = context(routes.getContent().stream()
                .map(r -> domainRepository.findById(r.getDomainId()).orElse(null))
                .filter(d -> d != null).toList());
        List<AdminRouteView> content = routes.getContent().stream().map(route -> {
            Domain domain = ctx.domains.get(route.getDomainId());
            Vm vm = domain != null ? ctx.vms.get(domain.getVmId()) : null;
            return new AdminRouteView(route.getPublicId(),
                    domain != null ? domain.getPublicId() : null,
                    domain != null ? domain.getFqdn() : null,
                    domain != null ? domain.getKind() : null,
                    vm != null ? vm.getPublicId() : null, name(vm),
                    ctx.workspaceId(domain), ctx.workspaceName(domain),
                    ctx.orgId(domain), ctx.orgName(domain),
                    route.getTargetPort(), route.getProtocol(), route.getStatus(),
                    route.getAppliedGeneration(), route.getAppliedAt(), route.getLastError(),
                    route.getUpdatedAt());
        }).toList();
        return PageResponse.of(content, routes);
    }

    /**
     * The admin domain listing. Names held through their release grace are in
     * it — the query hides REMOVED only, and a release leaves the row ACTIVE —
     * so {@code releasedAt}/{@code reservedUntil} are what separate them from
     * a domain that simply has no route yet. Without that pair the two read
     * identically, and "why is this subdomain taken" has no answer here.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminDomainView> listDomains(AuthenticatedUser actor, UUID orgId,
            DomainKind kind, DomainStatus status, int page, int size) {
        OrgScope scope = scopedOrgId(actor, orgId);
        Page<Domain> domains = domainRepository.findAdmin(orgFilter(scope), name(kind), name(status),
                page(page, size));
        Context ctx = context(domains.getContent());
        List<AdminDomainView> content = domains.getContent().stream()
                .map(domain -> view(domain, ctx)).toList();
        return PageResponse.of(content, domains);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminCertificateView> listCertificates(AuthenticatedUser actor, UUID orgId,
            CertificateStatus status, Integer expiringInDays, int page, int size) {
        OrgScope scope = scopedOrgId(actor, orgId);
        Instant now = Instant.now();
        Page<Certificate> certs = expiringInDays != null
                ? certificateRepository.findAdminExpiring(orgFilter(scope), name(status),
                        now.plus(expiringInDays, ChronoUnit.DAYS), page(page, size))
                : certificateRepository.findAdmin(orgFilter(scope), name(status), page(page, size));
        // The certificate names its domain by public id, and unlike its sibling
        // listings this one had no domain load at all — hence the batch.
        Map<Long, UUID> domainIds = domainRepository.findAllById(certs.getContent().stream()
                        .map(Certificate::getDomainId).filter(java.util.Objects::nonNull)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(Domain::getId, Domain::getPublicId));
        List<AdminCertificateView> content = certs.getContent().stream()
                .map(cert -> new AdminCertificateView(cert.getPublicId(), cert.getKind(), cert.getStatus(),
                        cert.getScope(), domainIds.get(cert.getDomainId()), cert.getNotAfter(),
                        // a FAILED cert has no meaningful expiry countdown
                        cert.getStatus() == CertificateStatus.FAILED ? null
                                : daysUntilExpiry(now, cert.getNotAfter()),
                        cert.getLastError()))
                .toList();
        return PageResponse.of(content, certs);
    }

    /** One row as the administrator's listing reports it. */
    private AdminDomainView view(Domain domain, Context ctx) {
        Vm vm = ctx.vms.get(domain.getVmId());
        RouteStatus routeStatus = routeRepository
                .findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .map(Route::getStatus).orElse(null);
        var certStatus = assembler.certificateFor(domain).map(Certificate::getStatus).orElse(null);
        return new AdminDomainView(domain.getPublicId(),
                vm != null ? vm.getPublicId() : null, domain.getKind(),
                domain.getFqdn(), domain.getRootDomain(), domain.getStatus(),
                domain.getVerifiedAt(), domain.getReleasedAt(),
                assembler.reservedUntil(domain), domain.getRenewDueAt(),
                domain.getCreatedAt(), name(vm),
                ctx.workspaceId(domain), ctx.workspaceName(domain),
                ctx.orgId(domain), ctx.orgName(domain),
                routeStatus, certStatus, domain.getDnsStatus(), domain.getDnsLastError(),
                domain.getDnsAppliedAt(), domain.getUpdatedAt());
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
     * The record sets standing under one name, for an administrator.
     *
     * <p>Read only. What an administrator can do about a name they have had
     * reported is release it, and deciding that without being able to see where
     * it points means deciding blind — the whole question is usually what the
     * name resolves to. Editing the sets is deliberately not offered: it is a
     * deeper intervention than taking the name away, and it would leave the
     * owner with records they did not write and no notice that anything
     * changed.</p>
     */
    @Transactional(readOnly = true)
    public List<DnsRecordSetView> listRecords(AuthenticatedUser actor, UUID domainId) {
        Domain domain = requireReadableDomain(actor, domainId);
        return recordRepository
                .findByDomainIdAndStatusNotOrderByIdAsc(domain.getId(), DomainRecordStatus.REMOVED)
                .stream()
                .map(record -> new DnsRecordSetView(record.getName(), record.getType(),
                        record.getRrdatas(), record.getTtl(), record.getStatus(),
                        record.getLastError(), record.getAppliedAt()))
                .toList();
    }

    /**
     * Moves one name's renewal deadline.
     *
     * <p>The gentler half of intervention. A forced release takes a name away
     * now; this decides when its owner has to speak up, which is what a name
     * that should last a term needs, and equally what a name that should wind
     * down needs. The deadline is the only lifetime an external name has, so
     * moving it is the whole of the lever.</p>
     *
     * <p>Only for names with a deadline. A platform subdomain's life is its
     * VM's, and a custom domain's is its owner's DNS; neither has a deadline
     * here to move, and inventing one would be this screen making up a
     * lifetime the rest of the system does not honour.</p>
     */
    @Transactional
    public AdminDomainView updateRenewal(AuthenticatedUser actor, UUID domainId,
            UpdateDomainRenewalRequest form, String ip) {
        Domain domain = requireScopedDomain(actor, domainId);
        if (domain.getKind() != DomainKind.EXTERNAL) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                    "사용 기한이 없는 도메인입니다",
                    "외부 도메인만 사용 기한을 갖습니다. 다른 종류는 가상머신이나 소유자의 DNS가 수명을 정합니다.");
        }
        if (domain.getReleasedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_ACTIVE,
                    "이미 해제한 도메인입니다", "해제한 이름에는 사용 기한이 없습니다.");
        }
        // A deadline in the past is a forced release with a day's delay and no
        // notice: the nightly sweeper lapses it, the records come down, and the
        // owner is never told because the renewal notices only fire ahead of a
        // deadline that is still ahead. Taking a name away is what force-release
        // is for, and that path at least announces itself.
        if (!form.renewDueAt().isAfter(Instant.now())) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("renewDueAt",
                    "지난 시각으로는 옮길 수 없습니다. 지금 회수하려면 강제 해제를 쓰세요.")));
        }
        Instant before = domain.getRenewDueAt();
        domain.setRenewDueAt(form.renewDueAt());
        // The owner hears about it. A deadline is the one lever that decides
        // whether they keep the name, so moving it without saying so leaves
        // them planning against a date that is no longer real.
        notificationService.publish(DomainRecipients.of(notificationService, domain),
                NotificationEvent.DOMAIN_RENEWAL_DUE,
                Map.of("fqdn", domain.getFqdn(), "domainId", domain.getPublicId(),
                        "renewDueAt", form.renewDueAt(), "adminAdjusted", true),
                null);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DOMAIN_ADMIN_RENEWAL, "domain", domain.getPublicId(),
                reasonedArgs(domain, before, form), ip);
        return view(domain, context(List.of(domain)));
    }

    private static Map<String, Object> reasonedArgs(Domain domain, Instant before,
            UpdateDomainRenewalRequest form) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("fqdn", domain.getFqdn());
        args.put("previous", before);
        args.put("renewDueAt", form.renewDueAt());
        if (form.reason() != null && !form.reason().isBlank()) {
            args.put("reason", form.reason().strip());
        }
        return args;
    }

    /**
     * Admin takedown of a problem domain: route down (pushed to the proxy) and
     * the name freed at once, skipping the reservation grace a user's own
     * release gets. The owning workspace is told on the same channel as an admin
     * mapping delete — the audit row alone reaches no user, and a public
     * address disappearing must not be discovered from a dead link. A serving
     * domain also gets the UNPUBLISH entry in the VM's event history (parity
     * with the user-side release; a reserved row took no traffic down, so it
     * gets none — same as a user's immediate return).
     */
    @Transactional
    public MessageResponse forceRelease(AuthenticatedUser actor, UUID domainId, String ip) {
        Domain domain = requireScopedDomain(actor, domainId);
        boolean served = assembler.hasLiveRoute(domain);
        publishingService.forceTeardown(domain);
        // The VM's event log is where an admin release is announced for a
        // domain that serves one, and a domain that serves no VM has none.
        // Asked before the lookup rather than after, because a null id is a
        // refusal there and not an empty result.
        Vm vm = domain.getVmId() == null ? null
                : vmRepository.findById(domain.getVmId()).orElse(null);
        if (vm != null) {
            if (served) {
                vmEventRepository.save(new VmEvent(vm.getId(), VmEventType.UNPUBLISH, actor.id(),
                        VmActorKind.ADMIN, "관리자 해제 — " + domain.getFqdn()));
            }
            notificationService.publish(
                    notificationService.vmResponsibleIds(vm),
                    NotificationEvent.DOMAIN_ADMIN_RELEASED,
                    Map.of("vmId", vm.getPublicId(), "vmName", vm.getName(),
                            "fqdn", domain.getFqdn()),
                    null);
        } else {
            // The event log is the VM's, but the notice is not: a public
            // address disappearing must not be discovered from a dead link,
            // whether or not the name had a VM behind it. A domain that stands
            // on its own answers who is responsible from its own access list.
            notificationService.publish(
                    DomainRecipients.of(notificationService, domain),
                    NotificationEvent.DOMAIN_ADMIN_RELEASED,
                    Map.of("domainId", domain.getPublicId(), "fqdn", domain.getFqdn()),
                    null);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DOMAIN_FORCE_RELEASE, "domain", domain.getPublicId(),
                Map.of("fqdn", domain.getFqdn()), ip);
        return new MessageResponse("도메인을 강제 해제했습니다. 라우트 제거가 곧 적용되며, 이름은 즉시 회수됩니다.");
    }

    /**
     * Forced ownership re-verification of a custom domain. Same trigger as the
     * user op minus the per-user rate limit — the per-domain in-flight dedupe
     * in {@link DomainVerificationJob} still bounds the load.
     */
    @Transactional
    public MessageResponse verify(AuthenticatedUser actor, UUID publicDomainId, String ip) {
        Domain domain = requireScopedDomain(actor, publicDomainId);
        long domainId = domain.getId();
        if (domain.getKind() != DomainKind.CUSTOM) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOMAIN_NOT_CUSTOM,
                    "검증할 수 없는 도메인입니다", "플랫폼 서브도메인은 소유권 검증이 필요하지 않습니다.");
        }
        runAfterCommit(() -> domainVerificationJob.requestVerify(domainId));
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.DOMAIN_ADMIN_VERIFY, "domain", domain.getPublicId(),
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
    public MessageResponse applyRoute(AuthenticatedUser actor, UUID routeId, String ip) {
        Route route = routeRepository.findByPublicId(routeId)
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
                "route", route.getPublicId(), Map.of("fqdn", domain.getFqdn()), ip);
        return new MessageResponse("라우트 재적용을 접수했습니다. 잠시 후 적용 상태가 갱신됩니다.");
    }

    /**
     * Target resolution with the admin 404 mask: unknown id, already-REMOVED
     * domain, and an org-tier actor naming another org's domain all answer the
     * same 404.
     */
    private Domain requireScopedDomain(AuthenticatedUser actor, UUID domainId) {
        return requireScopedDomain(actor, domainId, false);
    }

    /**
     * The same resolution for somebody who is only looking.
     *
     * <p>Split because the two questions have different answers once a
     * read-only role exists: what may be seen is every organisation the
     * account holds a role in, what may be changed is the subset it
     * administers or operates. Every caller of this class was a write until an
     * external name's records became readable here, and a read that asks the
     * write question hides an organisation's own rows from its own viewer.</p>
     */
    private Domain requireReadableDomain(AuthenticatedUser actor, UUID domainId) {
        return requireScopedDomain(actor, domainId, true);
    }

    private Domain requireScopedDomain(AuthenticatedUser actor, UUID domainId, boolean forRead) {
        Domain domain = domainRepository.findByPublicId(domainId)
                .orElseThrow(AdminPublishingService::domainNotFound);
        if (domain.getStatus() == DomainStatus.REMOVED) {
            throw domainNotFound();
        }
        requireScope(actor, domain, forRead);
        return domain;
    }

    /**
     * The organisation scope of one domain, read off the domain.
     *
     * <p>It used to be read off the VM, which made two problems out of one
     * lookup. The listing beside it scopes on the row's own organisation, so
     * the two answered from different sources and could disagree — a row
     * visible in the list and absent when opened. And a domain that serves no
     * VM has no id to look up: the repository refuses a null one, so the
     * refusal this guard exists to produce arrived as a server error
     * instead.</p>
     */
    private void requireScope(AuthenticatedUser actor, Domain domain) {
        requireScope(actor, domain, false);
    }

    private void requireScope(AuthenticatedUser actor, Domain domain, boolean forRead) {
        if (!actor.role().isOrgTier()) {
            return;
        }
        boolean allowed = forRead ? actor.reads(domain.getOrgId())
                : actor.operates(domain.getOrgId());
        if (!allowed) {
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

    private OrgScope scopedOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null
                : orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        return AdminOrgScope.read(actor, orgId, requested);
    }

    /** Null for an unrestricted scope, which is what the repositories expect. */
    private static Collection<Long> orgFilter(OrgScope scope) {
        return scope.isUnrestricted() ? null : scope.orgIds();
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

    /**
     * Batch-resolves the VM, workspace and organisation context for a set of
     * domains.
     *
     * <p>Workspace and organisation come off the domain rows, not off their
     * VMs. Read through the VM, a domain that serves none reports no workspace
     * and no organisation, which in the admin listing is a row with blanks
     * where its owner should be — and in the org-scoped listing, a row that is
     * not there at all. The VM lookup stays for the VM's own name and id, and
     * skips the rows that have none.</p>
     */
    private Context context(List<Domain> domains) {
        Set<Long> vmIds = domains.stream().map(Domain::getVmId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Vm> vms = byId(vmRepository.findAllById(vmIds), Vm::getId);
        Set<Long> workspaceIds = domains.stream().map(Domain::getWorkspaceId).collect(Collectors.toSet());
        Set<Long> orgIds = domains.stream().map(Domain::getOrgId).collect(Collectors.toSet());
        Map<Long, Workspace> workspaces = byId(workspaceRepository.findAllById(workspaceIds), Workspace::getId);
        Map<Long, Org> orgs = byId(orgRepository.findAllById(orgIds), Org::getId);
        return new Context(byId(domains, Domain::getId), vms, workspaces, orgs);
    }

    private static <T> Map<Long, T> byId(Iterable<T> entities, Function<T, Long> idOf) {
        Map<Long, T> map = new java.util.HashMap<>();
        entities.forEach(e -> map.put(idOf.apply(e), e));
        return map;
    }

    private record Context(Map<Long, Domain> domains, Map<Long, Vm> vms, Map<Long, Workspace> workspaces,
            Map<Long, Org> orgs) {

        String workspaceName(Domain domain) {
            Workspace workspace = domain == null ? null : workspaces.get(domain.getWorkspaceId());
            return workspace != null ? workspace.getName() : null;
        }

        String orgName(Domain domain) {
            Org org = domain == null ? null : orgs.get(domain.getOrgId());
            return org != null ? org.getName() : null;
        }

        UUID workspaceId(Domain domain) {
            Workspace workspace = domain == null ? null : workspaces.get(domain.getWorkspaceId());
            return workspace != null ? workspace.getPublicId() : null;
        }

        UUID orgId(Domain domain) {
            Org org = domain == null ? null : orgs.get(domain.getOrgId());
            return org != null ? org.getPublicId() : null;
        }
    }
}
