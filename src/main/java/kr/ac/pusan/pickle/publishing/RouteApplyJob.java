package kr.ac.pusan.pickle.publishing;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import kr.ac.pusan.pickle.config.PublishingProperties;
import kr.ac.pusan.pickle.ipam.IpAddressResolver;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.publishing.agent.AgentStatus;
import kr.ac.pusan.pickle.publishing.agent.ApplyOutcome;
import kr.ac.pusan.pickle.publishing.agent.ApplyRequest;
import kr.ac.pusan.pickle.publishing.agent.ProxyAgentClient;
import kr.ac.pusan.pickle.publishing.agent.ProxyAgentUnreachableException;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pushes one route's desired state to proxy-agent (the proxy-agent control contract).
 * The endpoint wrote intent (route row + generation) and enqueued this after
 * commit; here we resolve the current desired state from the DB and call the
 * agent, recording the outcome on the route.
 *
 * <p><b>SSRF enforcement point.</b> The upstream target IP is resolved
 * server-side from the VM's own live allocation ({@link IpAddressResolver}) —
 * never from any request field (there is none) — so a route can only ever point
 * at the VM's own vmbr2 address.</p>
 *
 * <p><b>Three-phase, no transaction across the network.</b> A custom-domain
 * apply runs certificate issuance inline on the agent and can take minutes;
 * holding a DB transaction (and the route row lock) for that long would drain
 * the connection pool under a burst of applies and stall unrelated requests.
 * So the flow is: a short transaction reads the desired state under the row
 * lock ({@code prepare}), the agent call happens with no transaction and no
 * lock, and a second short transaction re-locks the row and records the
 * outcome <em>only if the generation is still the one that was pushed</em>
 * ({@code record}). If the world changed during the call — a teardown, a port
 * edit, a revive; every desired-state change bumps the generation — the
 * outcome is discarded and the newer intent's own apply (or the recurring
 * {@link RouteReconcileJob}) converges the agent. Callers must not wrap
 * {@link #applyNow} in a transaction of their own, or the call would be pulled
 * back inside one.</p>
 *
 * <p>Idempotent/desired-state: re-running is safe, and a stale generation is a
 * 409 no-op on the agent. Failure handling splits on what the agent said:
 * a 422 (config rejected) records FAILED without throwing — retrying the same
 * config cannot succeed, recovery is re-publish/verify-retry or the admin resync
 * — while a TRANSPORT failure (agent unreachable, no verdict) keeps the desired
 * status, commits, and then throws so JobRunr retries with backoff. Outages
 * longer than the retry window are healed by the recurring
 * {@link RouteReconcileJob}.</p>
 */
@Component
public class RouteApplyJob {

    private static final Logger log = LoggerFactory.getLogger(RouteApplyJob.class);

    /** JobRunr retry budget for TRANSPORT failures (the only failure that throws). */
    static final int TRANSPORT_RETRIES = 4;

    /** Bounded /status confirmation after a custom-domain apply (certbot runs
     * inline during the apply, so the first poll is normally definitive). */
    private static final int CERT_STATUS_ATTEMPTS = 2;
    private static final Duration CERT_STATUS_RETRY_DELAY = Duration.ofSeconds(1);

    private final RouteRepository routeRepository;
    private final DomainRepository domainRepository;
    private final CertificateRepository certificateRepository;
    private final VmRepository vmRepository;
    private final IpAddressResolver ipAddressResolver;
    private final ProxyAgentClient proxyAgentClient;
    private final PublishingProperties properties;
    private final PublicationAssembler assembler;
    private final TransactionTemplate transactionTemplate;
    private final NotificationService notificationService;
    private final RouteGenerations routeGenerations;

    public RouteApplyJob(RouteRepository routeRepository, DomainRepository domainRepository,
            CertificateRepository certificateRepository, VmRepository vmRepository,
            IpAddressResolver ipAddressResolver, ProxyAgentClient proxyAgentClient,
            PublishingProperties properties, PublicationAssembler assembler,
            TransactionTemplate transactionTemplate,
            NotificationService notificationService, RouteGenerations routeGenerations) {
        this.routeRepository = routeRepository;
        this.domainRepository = domainRepository;
        this.certificateRepository = certificateRepository;
        this.vmRepository = vmRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.proxyAgentClient = proxyAgentClient;
        this.properties = properties;
        this.assembler = assembler;
        this.transactionTemplate = transactionTemplate;
        this.notificationService = notificationService;
        this.routeGenerations = routeGenerations;
    }

    /**
     * Enqueued entry point. A TRANSPORT outcome throws — after the recording
     * transaction committed — so JobRunr retries it with backoff; the agent's
     * stale-generation 409 guard makes a superseded retry a no-op. Every other
     * outcome (incl. 422 FAILED) is final for this enqueue.
     */
    @Job(name = "route-apply %0", retries = TRANSPORT_RETRIES)
    public void apply(long routeId) {
        if (applyNow(routeId) == ApplyOutcome.Kind.TRANSPORT) {
            throw new ProxyAgentUnreachableException(
                    "proxy-agent 연결 실패 — route-apply " + routeId + " 재시도 예약");
        }
    }

    /**
     * Same as {@link #apply} but reports the outcome — the VM-deletion teardown
     * ({@link PublishingTeardownService}) and the recurring {@link RouteReconcileJob}
     * push synchronously and act on the result. The two DB phases each run in
     * their own short transaction ({@link TransactionTemplate}); the agent call
     * between them holds neither a transaction nor a row lock.
     */
    public ApplyOutcome.Kind applyNow(long routeId) {
        Prep prep = transactionTemplate.execute(tx -> prepare(routeId));
        if (prep instanceof Skip skip) {
            return skip.kind();
        }
        Push push = (Push) prep;
        ApplyOutcome outcome = proxyAgentClient.apply(push.request());
        // The cert confirmation is a second agent round trip (GET /status, with
        // a bounded retry sleep) — it must happen out here for the same reason
        // the apply call does.
        CertVerdict certVerdict = outcome.kind() == ApplyOutcome.Kind.APPLIED
                && !push.absent() && push.custom() ? probeCert(push.fqdn()) : null;
        return transactionTemplate.execute(tx -> record(push, outcome, certVerdict));
    }

    /** Result of the prepare phase: either push this request, or stop here. */
    private sealed interface Prep permits Push, Skip {
    }

    /** Desired state snapshot to push — {@code generation} is the CAS token. */
    private record Push(long routeId, long domainId, long generation, boolean absent,
            boolean custom, String fqdn, ApplyRequest request) implements Prep {
    }

    /** Nothing to push; {@code kind} is what {@link #applyNow} reports. */
    private record Skip(ApplyOutcome.Kind kind) implements Prep {
    }

    /**
     * Phase 1 (own short transaction): read the desired state under the row
     * lock and build the agent request. Corrective writes (stray-route removal,
     * hold errors, no-IP FAILED) also land here, under the lock.
     */
    private Prep prepare(long routeId) {
        Route route = routeRepository.findByIdForApply(routeId).orElse(null);
        if (route == null) {
            log.warn("route-apply skipped: route {} not found", routeId);
            return new Skip(null);
        }
        Domain domain = domainRepository.findById(route.getDomainId()).orElseThrow();
        boolean absent = route.getStatus() == RouteStatus.REMOVED;
        // A removed domain must never serve. Finding its route still live means
        // the two disagree, and the safe reading of that disagreement is the
        // domain's: push the removal rather than hold, because holding leaves the
        // vhost answering for a domain — often a VM — that is already gone, and
        // the deletion pipeline reads a hold as nothing left to do.
        if (!absent && domain.getStatus() == DomainStatus.REMOVED) {
            route.setStatus(RouteStatus.REMOVED);
            absent = true;
            if (liveClaimant(domain).isEmpty()) {
                // The generation must outrank what the agent already applied, or
                // the removal is refused as stale and the vhost survives the
                // correction. Reaching here means something wrote the route back
                // under us, so its generation is exactly the one the agent has.
                // With a claimant the bump is exactly wrong — see below.
                route.setGeneration(routeGenerations.next());
            }
        }
        if (absent) {
            // A freed name may already have a new owner (custom domains release
            // their FQDN the moment they are deleted). Once another live domain
            // row holds this FQDN, the name — and its vhost — belong to that
            // route's convergence, and pushing this removal with an OUTRANKING
            // generation would take the new owner's vhost down. With the older
            // token the push stays safe (the agent refuses it once the new
            // owner has applied), so only the outranking case retires here; the
            // refused case retires on its 409 (see recordSuperseded).
            Optional<Route> claimant = liveClaimant(domain);
            if (claimant.isPresent()
                    && claimant.get().getGeneration() < route.getGeneration()) {
                settleSuperseded(route, domain);
                return new Skip(null);
            }
        }
        // Local backstop for the platform invariant: a PRESENT push may only
        // serve a verified (ACTIVE) domain. Every enqueue site guards this
        // already; enforcing it at the single execution choke point keeps the
        // invariant from depending on all of them staying correct.
        if (!absent && domain.getStatus() != DomainStatus.ACTIVE) {
            // Surface the hold to operators (admin route view shows lastError).
            route.setLastError("도메인이 검증 완료(ACTIVE) 상태가 아니어서 적용을 보류했습니다. (현재 "
                    + domain.getStatus() + ")");
            log.warn("route-apply skipped: route {} is live but domain {} is {} — "
                    + "refusing to push an unverified/released domain", routeId,
                    domain.getFqdn(), domain.getStatus());
            return new Skip(null);
        }
        ApplyRequest request = absent
                ? ApplyRequest.absent(domain.getFqdn(), route.getGeneration())
                : presentRequest(domain, route);
        if (request == null) {
            return new Skip(ApplyOutcome.Kind.FAILED); // recorded already (no live IP)
        }
        return new Push(route.getId(), domain.getId(), route.getGeneration(), absent,
                domain.getKind() == DomainKind.CUSTOM, domain.getFqdn(), request);
    }

    /**
     * Phase 3 (own short transaction): re-lock the route and record the agent's
     * verdict, but only when the generation is still the one that was pushed —
     * anything else means a newer desired state was written during the call,
     * and this (now historical) outcome must not overwrite it. The discarded
     * state is re-converged by the newer intent's own apply or the reconciler.
     */
    private ApplyOutcome.Kind record(Push push, ApplyOutcome outcome, CertVerdict certVerdict) {
        Route route = routeRepository.findByIdForApply(push.routeId()).orElse(null);
        if (route == null) {
            log.warn("route-apply outcome dropped: route {} disappeared during the call",
                    push.routeId());
            return outcome.kind();
        }
        if (route.getGeneration() != push.generation()) {
            log.info("route-apply outcome discarded for {}: generation moved {} -> {} during "
                    + "the call", push.fqdn(), push.generation(), route.getGeneration());
            return outcome.kind();
        }
        Domain domain = domainRepository.findById(push.domainId()).orElseThrow();
        switch (outcome.kind()) {
            case APPLIED -> recordApplied(route, domain, push.absent(), outcome, certVerdict);
            case STALE -> recordSuperseded(route, domain, push.absent(), outcome);
            case FAILED -> recordFailed(route, domain, push.absent(), outcome.error());
            case TRANSPORT -> recordTransport(route, domain, outcome.error());
        }
        return outcome.kind();
    }

    /**
     * The live route currently holding this domain's FQDN under ANOTHER live
     * domain row — i.e. the name's new owner after an immediate release. At
     * most one exists (the partial unique index on live domains).
     */
    private Optional<Route> liveClaimant(Domain domain) {
        return routeRepository.findLiveClaimant(domain.getFqdn(), domain.getId());
    }

    /**
     * Finalizes a removal that the FQDN's new owner has made moot: marking the
     * confirmed generation current takes the route out of the reconciler's
     * unconfirmed set, so nothing keeps re-pushing a removal that would fight
     * the new owner. A stray vhost (if one survives) is replaced by the new
     * owner's own apply, which always carries the newer generation.
     */
    private void settleSuperseded(Route route, Domain domain) {
        route.setAppliedGeneration(route.getGeneration());
        log.info("route-apply removal for {} retired: the fqdn is live under a newer route — "
                + "its owner's apply converges the vhost", domain.getFqdn());
    }

    private ApplyRequest presentRequest(Domain domain, Route route) {
        Vm vm = vmRepository.findById(domain.getVmId()).orElse(null);
        String targetIp = vm == null ? null
                : ipAddressResolver.liveHostIp(vm.getIpAllocationId(), vm.getId());
        if (targetIp == null) {
            recordFailed(route, domain, false, "VM에 할당된 내부 IP를 찾을 수 없습니다.");
            return null;
        }
        return ApplyRequest.present(domain.getFqdn(), route.getGeneration(), targetIp,
                route.getTargetPort(), assembler.certRefFor(domain));
    }

    private void recordApplied(Route route, Domain domain, boolean absent, ApplyOutcome outcome,
            CertVerdict certVerdict) {
        Long appliedGen = outcome.generation() != null ? outcome.generation() : route.getGeneration();
        route.setAppliedGeneration(appliedGen);
        route.setAppliedAt(Instant.now());
        route.setLastError(null);
        if (!absent) {
            route.setStatus(RouteStatus.APPLIED);
            if (certVerdict != null) {
                markCert(domain, certVerdict.status(), certVerdict.error());
            }
            // Deduped per domain: reconcile re-applies and generation bumps do
            // not re-announce an already-connected domain.
            notifyDomainOutcome(domain, NotificationEvent.DOMAIN_CONNECT_DONE, null,
                    "domain_connect_done:" + domain.getId());
        }
        log.info("route-apply {} for {} (generation {})",
                absent ? "removed" : "applied", domain.getFqdn(), appliedGen);
    }

    /**
     * 409 STALE: the agent already holds a generation ≥ ours (e.g. a sync-all
     * snapshot superseded this apply). Recording that confirmed generation
     * settles the route's desired-state check, so {@link RouteReconcileJob}
     * does not re-push a superseded apply forever.
     */
    private void recordSuperseded(Route route, Domain domain, boolean absent,
            ApplyOutcome outcome) {
        if (absent) {
            if (liveClaimant(domain).isPresent()) {
                // The refusing generation is the fqdn's new owner going live
                // during our call. Re-bumping would hand the NEXT removal push
                // a token that outranks the owner's vhost — retire instead.
                settleSuperseded(route, domain);
                return;
            }
            // A removal must never be "confirmed" by someone else's PRESENT
            // apply winning the generation race — that would leave an orphan
            // live vhost. Outrank the applied generation instead, so the
            // reconciler re-pushes the ABSENT state with a winning token.
            route.setGeneration(routeGenerations.next());
            log.warn("route-apply removal superseded for {} — re-bumped to gen {} for re-push",
                    domain.getFqdn(), route.getGeneration());
            return;
        }
        if (outcome.generation() != null && outcome.generation() >= route.getGeneration()) {
            route.setAppliedGeneration(outcome.generation());
        }
        log.info("route-apply superseded for {} (gen {} ≤ applied {})",
                domain.getFqdn(), route.getGeneration(), outcome.generation());
    }

    /**
     * Transport failure: the agent never judged the config, so the desired
     * status (PENDING/REMOVED) — and a custom domain's cert state — stay
     * untouched; only the error is surfaced. Retry is the enqueue's backoff
     * ({@link #apply}) plus the recurring {@link RouteReconcileJob}.
     */
    private void recordTransport(Route route, Domain domain, String error) {
        route.setLastError(error);
        log.warn("route-apply transport failure for {}: {}", domain.getFqdn(), error);
    }

    private void recordFailed(Route route, Domain domain, boolean absent, String error) {
        route.setLastError(error);
        if (!absent) {
            route.setStatus(RouteStatus.FAILED);
            if (domain.getKind() == DomainKind.CUSTOM) {
                markCert(domain, CertificateStatus.FAILED, error);
            }
            // Deduped per generation: the reconcile job retrying the same
            // failed generation stays silent, a fresh publish attempt speaks.
            notifyDomainOutcome(domain, NotificationEvent.DOMAIN_CONNECT_FAILED, error,
                    "domain_connect_failed:" + domain.getId() + ":g" + route.getGeneration());
        }
        log.warn("route-apply failed for {}: {}", domain.getFqdn(), error);
    }

    /** Group OWNER/EDITOR notice for a route outcome (same tx as the record). */
    private void notifyDomainOutcome(Domain domain, NotificationEvent event, String reason,
            String dedupKey) {
        Vm vm = vmRepository.findById(domain.getVmId()).orElse(null);
        if (vm == null) {
            return;
        }
        Map<String, Object> args = reason == null
                ? Map.of("fqdn", domain.getFqdn(), "vmId", vm.getId())
                : Map.of("fqdn", domain.getFqdn(), "vmId", vm.getId(), "reason", reason);
        notificationService.publish(notificationService.vmResponsibleIds(vm),
                event, args, dedupKey);
    }

    /** What the agent's {@code GET /status} said about a just-applied cert. */
    private record CertVerdict(CertificateStatus status, String error) {
    }

    /**
     * Probes the agent's {@code GET /status} for the custom domain's cert:
     * ACTIVE only on a confirmed OK (never a fabricated {@code notAfter} for an
     * unconfirmed cert), FAILED with the agent's error, and RENEWING when the
     * agent is unreachable or still PENDING — the verify retry
     * ({@code POST /domains/{id}/verify}) re-triggers issuance. Runs outside
     * any transaction; the verdict is written by the record phase.
     */
    private CertVerdict probeCert(String fqdn) {
        for (int attempt = 1; attempt <= CERT_STATUS_ATTEMPTS; attempt++) {
            Optional<AgentStatus.CertState> cert = proxyAgentClient.status()
                    .flatMap(status -> status.cert(fqdn));
            if (cert.isPresent()) {
                switch (cert.get().state()) {
                    case OK -> {
                        return new CertVerdict(CertificateStatus.ACTIVE, null);
                    }
                    case FAILED -> {
                        return new CertVerdict(CertificateStatus.FAILED, cert.get().error());
                    }
                    case PENDING -> {
                        // still issuing — retry below, else leave RENEWING
                    }
                }
            }
            if (attempt < CERT_STATUS_ATTEMPTS) {
                try {
                    Thread.sleep(CERT_STATUS_RETRY_DELAY.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("cert for {} not confirmed on agent /status — left RENEWING", fqdn);
        return new CertVerdict(CertificateStatus.RENEWING, null);
    }

    private void markCert(Domain domain, CertificateStatus status, String error) {
        certificateRepository.findFirstByDomainIdAndStatusNot(domain.getId(), CertificateStatus.REVOKED)
                .ifPresent(cert -> {
                    cert.setStatus(status);
                    cert.setLastError(error);
                    if (status == CertificateStatus.ACTIVE) {
                        cert.setNotAfter(Instant.now().plus(properties.leCertValidityDays(), ChronoUnit.DAYS));
                    }
                });
        if (status == CertificateStatus.FAILED) {
            // Operators watch cert issuance/renewal — HIGH to every SYS_ADMIN,
            // deduped per domain so repeated failed applies stay quiet.
            notificationService.publish(notificationService.sysAdminIds(),
                    NotificationEvent.CERT_FAILURE,
                    Map.of("fqdn", domain.getFqdn(),
                            "reason", error != null ? error : "원인 미상 (에이전트 응답 없음)"),
                    "cert_failure:" + domain.getId());
        }
    }
}
