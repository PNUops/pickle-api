package kr.ac.pusan.pickle.sshgw;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.SshGatewayProperties;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.ipam.IpAddressResolver;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.sshgw.dto.RouteRequest;
import kr.ac.pusan.pickle.sshgw.dto.RouteResponse;
import kr.ac.pusan.pickle.sshkey.UserSshKey;
import kr.ac.pusan.pickle.sshkey.UserSshKeyRepository;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an incoming SSH slug to an upstream route for sshpiper
 * (the per-user SSH gateway route contract (v2)). The check order is normative
 * and fail-closed, encoding the precedence <b>global kill switch &gt; admin
 * per-VM block &gt; user setting</b>: rate limit → kill switch → slug exists →
 * RUNNING → per-VM block → identity (publickey fingerprint must map to a
 * registered key whose owner is a MEMBER+ of the VM's group, or password path
 * requires the per-VM {@code ssh_password_enabled} opt-in) → a collected host
 * key to pin → a live IP.
 *
 * <p><b>This call is an authorization decision, not a session record</b>
 * (gate-C fix, 2026-07-18): it runs on an <b>unauthenticated</b> offered key
 * (sshpiperd's query-phase callback fires before signature verification), so a
 * public key anyone can offer must never attribute a session to its owner.
 * Therefore:
 * <ul>
 *   <li><b>Allowed lookups are not audited here</b> — the authenticated,
 *       attributed record is the {@code sshgw.session} event written by
 *       {@link SshGatewaySessionService} at PipeStart (post-verification).</li>
 *   <li><b>Denials</b> are audited synchronously as {@code sshgw.route_denied}
 *       (a security signal) but with <b>{@code actor} = null even when the
 *       fingerprint resolved to a user</b> — attributing a denial to the
 *       resolved user would let anyone stamp "victim denied at VM X" into the
 *       victim's trail by offering their public key. The fingerprint/keyId stay
 *       in {@code detail} for operators, without becoming an actor.</li>
 * </ul>
 */
@Service
public class SshGatewayRouteService {

    /** Upstream SSH port on every guest VM (fixed). */
    private static final int UPSTREAM_SSH_PORT = 22;

    /** Per-client (reported sourceIp) lookup budget — see class javadoc. */
    private static final String SOURCE_RATE_LIMIT_SCOPE = "sshgw_route_src";

    private final VmRepository vmRepository;
    private final IpAddressResolver ipAddressResolver;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final RateLimitService rateLimitService;
    private final SshGatewayProperties properties;
    private final UserSshKeyRepository sshKeyRepository;
    private final UserRepository userRepository;
    private final VmAccessService vmAccessService;
    private final VmSettingsService vmSettingsService;

    public SshGatewayRouteService(VmRepository vmRepository, IpAddressResolver ipAddressResolver,
            SettingsService settingsService, AuditService auditService,
            RateLimitService rateLimitService, SshGatewayProperties properties,
            UserSshKeyRepository sshKeyRepository, UserRepository userRepository,
            VmAccessService vmAccessService, VmSettingsService vmSettingsService) {
        this.vmRepository = vmRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.sshKeyRepository = sshKeyRepository;
        this.userRepository = userRepository;
        this.vmAccessService = vmAccessService;
        this.vmSettingsService = vmSettingsService;
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
    public RouteOutcome resolve(RouteRequest request, String gatewayPeer) {
        Context ctx = new Context(request, gatewayPeer);

        // Per-client throttle before anything else, so a rate-limited client
        // cannot even probe the kill switch or slug space. Keyed on the reported
        // sourceIp. The counter and the audit both commit in their own tx.
        try {
            rateLimitService.hit(SOURCE_RATE_LIMIT_SCOPE, ctx.sourceIp(),
                    properties.rateLimitPerMinute());
        } catch (ApiException rateLimited) {
            auditDenied(ctx, null, ErrorCodes.RATE_LIMITED);
            throw rateLimited; // → 429 problem+json with Retry-After
        }

        // 1) Global kill switch first — a disabled gateway reveals nothing about
        //    which slugs exist, and it outranks any per-VM opt-in.
        if (!settingsService.bool(SettingsService.SSH_GATEWAY_ENABLED, false)) {
            return deny(ctx, null, RouteOutcome.forbidden(ErrorCodes.SSHGW_GATEWAY_DISABLED));
        }

        // 2) Slug → VM.
        Optional<Vm> found = vmRepository.findByHostname(ctx.slug());
        if (found.isEmpty()) {
            return deny(ctx, null, RouteOutcome.notFound(ErrorCodes.SSHGW_ROUTE_NOT_FOUND));
        }
        Vm vm = found.get();

        // 3) RUNNING, 4) not per-VM blocked.
        if (vm.getStatus() != VmStatus.RUNNING) {
            return deny(ctx, vm.getId(), RouteOutcome.forbidden(ErrorCodes.SSHGW_VM_NOT_RUNNING));
        }
        if (vm.isSshGatewayBlocked()) {
            return deny(ctx, vm.getId(), RouteOutcome.forbidden(ErrorCodes.SSHGW_VM_BLOCKED));
        }

        // 5) Identity: publickey (fingerprint → key → member) or password opt-in.
        //    Identification fills detail's keyId but never an audit actor here.
        if (RouteRequest.AUTH_PUBLICKEY.equals(ctx.authMethod())) {
            Optional<UserSshKey> key = ctx.fingerprint() == null || ctx.fingerprint().isBlank()
                    ? Optional.empty()
                    : sshKeyRepository.findByFingerprintSha256(ctx.fingerprint());
            if (key.isEmpty()) {
                return deny(ctx, vm.getId(), RouteOutcome.forbidden(ErrorCodes.SSHGW_KEY_UNKNOWN));
            }
            ctx.identify(key.get());
            // The owner must still be ACTIVE (a disabled/withdrawn account loses
            // gateway access immediately). Reuse the least-leaky "unknown key"
            // code so a suspended owner is indistinguishable from an unregistered
            // key. Withdrawal also deletes the key rows, so this mainly covers
            // DISABLED (whose rows survive).
            if (userRepository.findById(key.get().getUserId())
                    .filter(owner -> owner.getStatus() == UserStatus.ACTIVE).isEmpty()) {
                return deny(ctx, vm.getId(), RouteOutcome.forbidden(ErrorCodes.SSHGW_KEY_UNKNOWN));
            }
            // VIEWER and non-members are denied identically (one code, no oracle).
            if (!vmAccessService.of(vm, key.get().getUserId()).atLeast(GroupMemberRole.MEMBER)) {
                return deny(ctx, vm.getId(), RouteOutcome.forbidden(ErrorCodes.SSHGW_KEY_NOT_MEMBER));
            }
        } else if (RouteRequest.AUTH_PASSWORD.equals(ctx.authMethod())) {
            if (!vmSettingsService.bool(vm.getId(), VmSettingsService.SSH_PASSWORD_ENABLED)) {
                return deny(ctx, vm.getId(),
                        RouteOutcome.forbidden(ErrorCodes.SSHGW_PASSWORD_DISABLED));
            }
        } else {
            // Unknown method — fail closed.
            return deny(ctx, vm.getId(), RouteOutcome.forbidden(ErrorCodes.SSHGW_KEY_UNKNOWN));
        }

        // 6) A collected host key to pin.
        if (vm.getSshHostKey() == null || vm.getSshHostKey().isBlank()) {
            return deny(ctx, vm.getId(), RouteOutcome.forbidden(ErrorCodes.SSHGW_NO_HOST_KEY));
        }

        // 7) A live IP allocation (SSRF-safe: owned + ALLOCATED guard).
        String ip = ipAddressResolver.liveHostIp(vm.getIpAllocationId(), vm.getId());
        if (ip == null) {
            return deny(ctx, vm.getId(), RouteOutcome.forbidden(ErrorCodes.SSHGW_ROUTE_NO_ADDRESS));
        }

        // Allowed lookups are NOT audited (gate-C): this ran on an unauthenticated
        // offered key. The authenticated per-user record is the /session call.
        // The stored ssh_host_key is newline-joined (one entry per host-key type
        // the VM presents); split it into the pinned hostKeys array.
        RouteResponse route = new RouteResponse(ip, UPSTREAM_SSH_PORT, vm.getSshUsername(),
                splitHostKeys(vm.getSshHostKey()));
        return RouteOutcome.granted(route);
    }

    private RouteOutcome deny(Context ctx, Long vmId, RouteOutcome outcome) {
        auditDenied(ctx, vmId, outcome.reason());
        return outcome;
    }

    /** Denials carry a null actor even after identification (see class javadoc). */
    private void auditDenied(Context ctx, Long vmId, String reason) {
        auditService.record(null, AuditService.ACTOR_ROLE_SSHGW, AuditService.SSHGW_ROUTE_DENIED,
                "vm", vmId, ctx.detail(reason), ctx.sourceIp());
    }

    /**
     * Splits the newline-joined stored host keys into one entry per type. Shared
     * with the web-terminal redeem step (the internal web-terminal contract) so both hops
     * pin against the identical multi-type set.
     */
    public static List<String> splitHostKeys(String stored) {
        if (stored == null) {
            return List.of();
        }
        return stored.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /** Per-request scratch: request fields plus the key resolved for detail. */
    private static final class Context {
        private final RouteRequest request;
        private final String gatewayPeer;
        private Long identifiedKeyId;

        Context(RouteRequest request, String gatewayPeer) {
            this.request = request;
            this.gatewayPeer = gatewayPeer;
        }

        String slug() {
            return request.slug();
        }

        String sourceIp() {
            return request.sourceIp();
        }

        String authMethod() {
            return request.authMethod();
        }

        String fingerprint() {
            return request.publicKeyFingerprint();
        }

        void identify(UserSshKey key) {
            this.identifiedKeyId = key.getId();
        }

        Map<String, Object> detail(String reason) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("slug", request.slug());
            // Reported by sshpiper from the PROXY header — recorded, never trusted.
            detail.put("sourceIp", request.sourceIp());
            // Transport fact: the authenticated TCP peer we accepted the call from.
            detail.put("gatewayPeer", gatewayPeer);
            detail.put("authMethod", request.authMethod());
            if (request.publicKeyFingerprint() != null && !request.publicKeyFingerprint().isBlank()) {
                detail.put("fingerprint", request.publicKeyFingerprint());
            }
            if (identifiedKeyId != null) {
                detail.put("keyId", identifiedKeyId);
            }
            if (request.connectionId() != null && !request.connectionId().isBlank()) {
                detail.put("connectionId", request.connectionId());
            }
            if (reason != null) {
                detail.put("reason", reason);
            }
            return detail;
        }
    }
}
