package kr.ac.pusan.pickle.sshgw;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.SshGatewayProperties;
import kr.ac.pusan.pickle.ipam.IpAddressResolver;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.sshgw.dto.RouteResponse;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an incoming SSH slug to an upstream route for sshpiper
 * (docs/api/internal.md Link 1, docs/plan/05). A per-client rate limit runs
 * first — keyed on the PROXY-recovered {@code sourceIp}, because the transport
 * peer is always the sshgw LXC and a peer-keyed bucket would let one internet
 * abuser lock out every student (the filter keeps only a global backstop); the
 * route lookup happens before SSH auth, so this is also the primary slug
 * brute-force throttle. Then all four gate checks must pass — the gateway is
 * globally enabled, the VM exists for that slug, is RUNNING, and is not per-VM
 * blocked — otherwise no route is returned and the denial is audited with a
 * machine-readable reason.
 *
 * <p>Every lookup (grant or denial) is audit-logged with the <b>reported</b>
 * client {@code sourceIp} (recovered by sshpiper from the PROXY header) in the
 * audit {@code ip} column, and the authenticated transport peer ({@code
 * gatewayPeer} — the sshgw LXC) plus the reported source kept as separate detail
 * fields, so a spoofed/buggy header can be told apart from the transport facts.
 * Audits are written directly (their own committed transaction) so a denial is
 * recorded reliably regardless of the read transaction.</p>
 */
@Service
public class SshGatewayRouteService {

    /** Upstream SSH port on every guest VM (fixed; docs/plan/05). */
    private static final int UPSTREAM_SSH_PORT = 22;

    /** Per-client (reported sourceIp) lookup budget — see class javadoc. */
    private static final String SOURCE_RATE_LIMIT_SCOPE = "sshgw_route_src";

    private final VmRepository vmRepository;
    private final IpAddressResolver ipAddressResolver;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final RateLimitService rateLimitService;
    private final SshGatewayProperties properties;

    public SshGatewayRouteService(VmRepository vmRepository, IpAddressResolver ipAddressResolver,
            SettingsService settingsService, AuditService auditService,
            RateLimitService rateLimitService, SshGatewayProperties properties) {
        this.vmRepository = vmRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    /**
     * The route decision plus the HTTP status the controller renders.
     * Exactly one of {@code route}/{@code reason} is non-null.
     */
    public record RouteOutcome(HttpStatus status, RouteResponse route, String reason) {

        static RouteOutcome granted(RouteResponse route) {
            return new RouteOutcome(HttpStatus.OK, route, null);
        }

        static RouteOutcome notFound(String reason) {
            return new RouteOutcome(HttpStatus.NOT_FOUND, null, reason);
        }

        static RouteOutcome forbidden(String reason) {
            return new RouteOutcome(HttpStatus.FORBIDDEN, null, reason);
        }

        public boolean granted() {
            return route != null;
        }
    }

    @Transactional(readOnly = true)
    public RouteOutcome resolve(String slug, String sourceIp, String gatewayPeer) {
        // Per-client throttle before anything else, so a rate-limited client
        // cannot even probe the kill switch or slug space. Keyed on the reported
        // sourceIp: spoofing it buys an attacker nothing (it only picks which
        // bucket THEY exhaust) while the honest value isolates real clients.
        // The counter and the audit both commit in their own transactions.
        try {
            rateLimitService.hit(SOURCE_RATE_LIMIT_SCOPE, sourceIp, properties.rateLimitPerMinute());
        } catch (ApiException rateLimited) {
            auditService.record(null, AuditService.ACTOR_ROLE_SSHGW, AuditService.SSHGW_ROUTE_DENIED,
                    "vm", null, detail(slug, sourceIp, gatewayPeer, ErrorCodes.RATE_LIMITED), sourceIp);
            throw rateLimited; // → 429 problem+json with Retry-After
        }

        // Global kill switch first: while the gateway is disabled we reveal
        // nothing about which slugs exist.
        if (!settingsService.bool(SettingsService.SSH_GATEWAY_ENABLED, false)) {
            return deny(RouteOutcome.forbidden(ErrorCodes.SSHGW_GATEWAY_DISABLED),
                    slug, null, sourceIp, gatewayPeer);
        }

        Optional<Vm> found = vmRepository.findByHostname(slug);
        if (found.isEmpty()) {
            return deny(RouteOutcome.notFound(ErrorCodes.SSHGW_ROUTE_NOT_FOUND),
                    slug, null, sourceIp, gatewayPeer);
        }
        Vm vm = found.get();

        if (vm.getStatus() != VmStatus.RUNNING) {
            return deny(RouteOutcome.forbidden(ErrorCodes.SSHGW_VM_NOT_RUNNING),
                    slug, vm.getId(), sourceIp, gatewayPeer);
        }
        if (vm.isSshGatewayBlocked()) {
            return deny(RouteOutcome.forbidden(ErrorCodes.SSHGW_VM_BLOCKED),
                    slug, vm.getId(), sourceIp, gatewayPeer);
        }

        // SSRF belt-and-suspenders (docs/plan/07): resolve strictly through the
        // owned + ALLOCATED guard, so a stale/reclaimed allocation pointer can
        // never route this slug to a different VM's address. The RUNNING gate
        // already implies a live allocation; this makes it defence in depth.
        String ip = ipAddressResolver.liveHostIp(vm.getIpAllocationId(), vm.getId());
        if (ip == null) {
            return deny(RouteOutcome.forbidden(ErrorCodes.SSHGW_ROUTE_NO_ADDRESS),
                    slug, vm.getId(), sourceIp, gatewayPeer);
        }

        RouteResponse route = new RouteResponse(ip, UPSTREAM_SSH_PORT, vm.getSshUsername());
        auditService.record(null, AuditService.ACTOR_ROLE_SSHGW, AuditService.SSHGW_ROUTE,
                "vm", vm.getId(), detail(slug, sourceIp, gatewayPeer, null), sourceIp);
        return RouteOutcome.granted(route);
    }

    private RouteOutcome deny(RouteOutcome outcome, String slug, Long vmId,
            String sourceIp, String gatewayPeer) {
        auditService.record(null, AuditService.ACTOR_ROLE_SSHGW, AuditService.SSHGW_ROUTE_DENIED,
                "vm", vmId, detail(slug, sourceIp, gatewayPeer, outcome.reason()), sourceIp);
        return outcome;
    }

    private static Map<String, Object> detail(String slug, String sourceIp, String gatewayPeer,
            String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("slug", slug);
        // Reported by sshpiper from the PROXY header — recorded, never trusted.
        detail.put("sourceIp", sourceIp);
        // Transport fact: the authenticated TCP peer we accepted the call from
        // (the sshgw LXC). The WireGuard relayPeer and sshpiper session id are
        // not carried in the v1 request; add them here when the plugin sends them.
        detail.put("gatewayPeer", gatewayPeer);
        if (reason != null) {
            detail.put("reason", reason);
        }
        return detail;
    }
}
