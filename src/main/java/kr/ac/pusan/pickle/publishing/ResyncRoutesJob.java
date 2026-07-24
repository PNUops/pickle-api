package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Full-manifest reconciliation (the proxy-agent control contract, {@code POST
 * /sync-all}), backing {@code POST /admin/routes/resync}. Renders every live
 * route from the DB (the authoritative manifest) and pushes it to proxy-agent,
 * which prunes agent-managed vhosts not in the manifest. Used after a proxy
 * rebuild, agent state loss, or drift suspicion.
 */
@Component
public class ResyncRoutesJob {

    private static final Logger log = LoggerFactory.getLogger(ResyncRoutesJob.class);

    private final RouteRepository routeRepository;
    private final DomainRepository domainRepository;
    private final VmRepository vmRepository;
    private final IpAddressResolver ipAddressResolver;
    private final RouteGenerations routeGenerations;
    private final ProxyAgentClient proxyAgentClient;
    private final PublishingProperties properties;

    public ResyncRoutesJob(RouteRepository routeRepository, DomainRepository domainRepository,
            VmRepository vmRepository, IpAddressResolver ipAddressResolver,
            RouteGenerations routeGenerations, ProxyAgentClient proxyAgentClient,
            PublishingProperties properties) {
        this.routeRepository = routeRepository;
        this.domainRepository = domainRepository;
        this.vmRepository = vmRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.routeGenerations = routeGenerations;
        this.proxyAgentClient = proxyAgentClient;
        this.properties = properties;
    }

    @Job(name = "route-resync (sync-all)", retries = 0)
    @Transactional
    public void run() {
        List<Route> live = routeRepository.findByStatusNot(RouteStatus.REMOVED);
        List<Route> included = new ArrayList<>();
        List<ApplyRequest> manifest = new ArrayList<>();
        for (Route route : live) {
            Domain domain = domainRepository.findById(route.getDomainId()).orElse(null);
            if (domain == null || domain.getStatus() != DomainStatus.ACTIVE) {
                continue; // only ACTIVE domains have a servable vhost
            }
            Vm vm = vmRepository.findById(domain.getVmId()).orElse(null);
            String targetIp = vm == null ? null
                    : ipAddressResolver.liveHostIp(vm.getIpAllocationId(), vm.getId());
            if (targetIp == null) {
                continue; // no live IP → nothing to render (SSRF guard: own IP only)
            }
            String certRef = domain.getKind() == DomainKind.CUSTOM
                    ? properties.letsEncryptCertRef() : properties.originCaCertRef();
            manifest.add(ApplyRequest.present(domain.getFqdn(), route.getGeneration(), targetIp,
                    route.getTargetPort(), certRef));
            included.add(route);
        }
        long snapshotGeneration = routeGenerations.next();
        ApplyOutcome outcome = proxyAgentClient.syncAll(snapshotGeneration, manifest);
        Instant now = Instant.now();
        switch (outcome.kind()) {
            case APPLIED -> included.forEach(route -> {
                route.setStatus(RouteStatus.APPLIED);
                route.setAppliedGeneration(route.getGeneration());
                route.setAppliedAt(now);
                route.setLastError(null);
            });
            case STALE -> log.info("route-resync superseded (snapshot {} stale)", snapshotGeneration);
            // 422 = all-or-nothing validation failure: the agent changed NOTHING,
            // so the routes keep their (still accurate) prior status — flipping
            // them FAILED would misreport every healthy vhost.
            case FAILED -> log.error("route-resync failed, agent tree unchanged: {}", outcome.error());
            // Agent unreachable: nothing changed either; the admin re-triggers,
            // and per-route drift self-heals via RouteReconcileJob.
            case TRANSPORT -> log.error("route-resync transport failure: {}", outcome.error());
        }
        log.info("route-resync pushed {} routes (outcome {})", manifest.size(), outcome.kind());
    }
}
