package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Full-manifest reconciliation (the proxy-agent control contract, {@code POST
 * /sync-all}), backing {@code POST /admin/routes/resync}. Renders every live
 * route from the DB (the authoritative manifest) and pushes it to proxy-agent,
 * which prunes agent-managed vhosts not in the manifest. Used after a proxy
 * rebuild, agent state loss, or drift suspicion.
 *
 * <p>Deliberately NOT one transaction: the manifest is read first, the
 * sync-all call runs with no transaction open, and each confirmation is then
 * written as its own generation-checked CAS
 * ({@link RouteRepository#confirmSyncedRoute}). A route whose desired state
 * changed while the call was on the wire — say a user released the domain, so
 * the route flipped REMOVED with a bumped generation — keeps that newer state:
 * flushing the pre-call snapshot over it would resurrect the vhost on the next
 * resync and freeze a released platform name in its reservation forever. The
 * skipped route's own apply/reconcile converges the agent. Per-route CAS also
 * means no row locks are held while the agent works, so a large manifest
 * cannot pin DB connections (same discipline as {@link RouteApplyJob}).</p>
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
    private final PublicationAssembler assembler;

    public ResyncRoutesJob(RouteRepository routeRepository, DomainRepository domainRepository,
            VmRepository vmRepository, IpAddressResolver ipAddressResolver,
            RouteGenerations routeGenerations, ProxyAgentClient proxyAgentClient,
            PublicationAssembler assembler) {
        this.routeRepository = routeRepository;
        this.domainRepository = domainRepository;
        this.vmRepository = vmRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.routeGenerations = routeGenerations;
        this.proxyAgentClient = proxyAgentClient;
        this.assembler = assembler;
    }

    /** The manifest slice of one route + the generation the CAS must match. */
    private record Included(long routeId, long generation) {
    }

    @Job(name = "route-resync (sync-all)", retries = 0)
    public void run() {
        List<Route> live = routeRepository.findByStatusNot(RouteStatus.REMOVED);
        List<Included> included = new ArrayList<>();
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
            manifest.add(ApplyRequest.present(domain.getFqdn(), route.getGeneration(), targetIp,
                    route.getTargetPort(), assembler.certRefFor(domain)));
            included.add(new Included(route.getId(), route.getGeneration()));
        }
        long snapshotGeneration = routeGenerations.next();
        ApplyOutcome outcome = proxyAgentClient.syncAll(snapshotGeneration, manifest);
        Instant now = Instant.now();
        switch (outcome.kind()) {
            case APPLIED -> {
                int skipped = 0;
                for (Included entry : included) {
                    if (routeRepository.confirmSyncedRoute(entry.routeId(), entry.generation(),
                            now) == 0) {
                        skipped++;
                    }
                }
                if (skipped > 0) {
                    // Not silent: these routes changed while the sync was on the
                    // wire and keep their newer state; their own apply/reconcile
                    // converges the agent.
                    log.info("route-resync left {} route(s) unconfirmed (changed mid-sync)",
                            skipped);
                }
            }
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
