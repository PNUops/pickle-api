package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import kr.ac.pusan.pickle.config.PublishingProperties;
import kr.ac.pusan.pickle.ipam.IpAddressResolver;
import kr.ac.pusan.pickle.publishing.agent.ApplyOutcome;
import kr.ac.pusan.pickle.publishing.agent.ApplyRequest;
import kr.ac.pusan.pickle.publishing.agent.ProxyAgentClient;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pushes one route's desired state to proxy-agent (docs/api/internal.md Link 2).
 * The endpoint wrote intent (route row + generation) and enqueued this after
 * commit; here we resolve the current desired state from the DB and call the
 * agent, recording the outcome on the route.
 *
 * <p><b>SSRF enforcement point.</b> The upstream target IP is resolved
 * server-side from the VM's own live allocation ({@link IpAddressResolver}) —
 * never from any request field (there is none) — so a route can only ever point
 * at the VM's own vmbr2 address (docs/plan/06, product-spec §12).</p>
 *
 * <p>Idempotent/desired-state: re-running is safe, and a stale generation is a
 * 409 no-op on the agent. The job records FAILED rather than throwing, so a
 * failed apply does not spin JobRunr retries against a config the agent already
 * rejected; recovery is re-publish or the admin resync (sync-all).</p>
 */
@Component
public class RouteApplyJob {

    private static final Logger log = LoggerFactory.getLogger(RouteApplyJob.class);

    private final RouteRepository routeRepository;
    private final DomainRepository domainRepository;
    private final CertificateRepository certificateRepository;
    private final VmRepository vmRepository;
    private final IpAddressResolver ipAddressResolver;
    private final ProxyAgentClient proxyAgentClient;
    private final PublishingProperties properties;

    public RouteApplyJob(RouteRepository routeRepository, DomainRepository domainRepository,
            CertificateRepository certificateRepository, VmRepository vmRepository,
            IpAddressResolver ipAddressResolver, ProxyAgentClient proxyAgentClient,
            PublishingProperties properties) {
        this.routeRepository = routeRepository;
        this.domainRepository = domainRepository;
        this.certificateRepository = certificateRepository;
        this.vmRepository = vmRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.proxyAgentClient = proxyAgentClient;
        this.properties = properties;
    }

    @Job(name = "route-apply %0", retries = 0)
    @Transactional
    public void apply(long routeId) {
        Route route = routeRepository.findById(routeId).orElse(null);
        if (route == null) {
            log.warn("route-apply skipped: route {} not found", routeId);
            return;
        }
        Domain domain = domainRepository.findById(route.getDomainId()).orElseThrow();
        boolean absent = route.getStatus() == RouteStatus.REMOVED;
        ApplyRequest request = absent ? absentRequest(domain, route) : presentRequest(domain, route);
        if (request == null) {
            return; // present() already recorded the failure (no live IP)
        }
        ApplyOutcome outcome = proxyAgentClient.apply(request);
        switch (outcome.kind()) {
            case APPLIED -> recordApplied(route, domain, absent, outcome);
            case STALE -> log.info("route-apply superseded for {} (gen {} ≤ applied {})",
                    domain.getFqdn(), route.getGeneration(), outcome.generation());
            case FAILED -> recordFailed(route, domain, absent, outcome.error());
        }
    }

    private ApplyRequest presentRequest(Domain domain, Route route) {
        Vm vm = vmRepository.findById(domain.getVmId()).orElse(null);
        String targetIp = vm == null ? null
                : ipAddressResolver.liveHostIp(vm.getIpAllocationId(), vm.getId());
        if (targetIp == null) {
            recordFailed(route, domain, false, "VM에 할당된 내부 IP를 찾을 수 없습니다.");
            return null;
        }
        String certRef = domain.getKind() == DomainKind.CUSTOM
                ? properties.letsEncryptCertRef() : properties.originCaCertRef();
        return ApplyRequest.present(domain.getFqdn(), route.getGeneration(), targetIp,
                route.getTargetPort(), certRef);
    }

    private ApplyRequest absentRequest(Domain domain, Route route) {
        return ApplyRequest.absent(domain.getFqdn(), route.getGeneration());
    }

    private void recordApplied(Route route, Domain domain, boolean absent, ApplyOutcome outcome) {
        Long appliedGen = outcome.generation() != null ? outcome.generation() : route.getGeneration();
        route.setAppliedGeneration(appliedGen);
        route.setAppliedAt(Instant.now());
        route.setLastError(null);
        if (!absent) {
            route.setStatus(RouteStatus.APPLIED);
            if (domain.getKind() == DomainKind.CUSTOM) {
                // The agent drove certbot after the vhost went live (internal.md).
                markCert(domain, CertificateStatus.ACTIVE, null);
            }
        }
        log.info("route-apply {} for {} (generation {})",
                absent ? "removed" : "applied", domain.getFqdn(), appliedGen);
    }

    private void recordFailed(Route route, Domain domain, boolean absent, String error) {
        route.setLastError(error);
        if (!absent) {
            route.setStatus(RouteStatus.FAILED);
            if (domain.getKind() == DomainKind.CUSTOM) {
                markCert(domain, CertificateStatus.FAILED, error);
            }
        }
        log.warn("route-apply failed for {}: {}", domain.getFqdn(), error);
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
    }
}
