package kr.ac.pusan.pickle.sshgw;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.SshGatewayProperties;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.ipam.IpAddressResolver;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.sshgw.dto.RouteRequest;
import kr.ac.pusan.pickle.sshgw.dto.RouteResponse;
import kr.ac.pusan.pickle.sshkey.UserSshKey;
import kr.ac.pusan.pickle.sshkey.UserSshKeyRepository;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vmsettings.VmSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an incoming SSH slug to an upstream route for sshpiper
 * (docs/api/internal.md Link 1 v2, docs/plan/05). The check order is normative
 * and fail-closed, encoding the precedence <b>global kill switch &gt; admin
 * per-VM block &gt; user setting</b>: rate limit → kill switch → slug exists →
 * RUNNING → per-VM block → identity (publickey fingerprint must map to a
 * registered key whose owner is a MEMBER+ of the VM's group, or password path
 * requires the per-VM {@code ssh_password_enabled} opt-in) → a collected host
 * key to pin → a live IP.
 *
 * <p><b>Audit attribution (v2):</b> once a public-key fingerprint resolves to a
 * registered key, the audit {@code actor} is the key owner's user id — including
 * on post-identification denials ({@code SSHGW_KEY_NOT_MEMBER}, and
 * {@code SSHGW_NO_HOST_KEY}/{@code SSHGW_ROUTE_NO_ADDRESS} reached on the
 * publickey path). Before identification and on the whole password path the
 * actor stays null (the password path's documented anonymity). Audits are
 * written directly (own committed tx) so denials are recorded on the read path;
 * the reported {@code sourceIp} (PROXY-recovered) is kept separate from the
 * authenticated {@code gatewayPeer}.</p>
 */
@Service
public class SshGatewayRouteService {

    private static final Logger log = LoggerFactory.getLogger(SshGatewayRouteService.class);

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
    private final UserSshKeyRepository sshKeyRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final VmSettingsService vmSettingsService;

    public SshGatewayRouteService(VmRepository vmRepository, IpAddressResolver ipAddressResolver,
            SettingsService settingsService, AuditService auditService,
            RateLimitService rateLimitService, SshGatewayProperties properties,
            UserSshKeyRepository sshKeyRepository, GroupMemberRepository groupMemberRepository,
            VmSettingsService vmSettingsService) {
        this.vmRepository = vmRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.sshKeyRepository = sshKeyRepository;
        this.groupMemberRepository = groupMemberRepository;
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
            auditDenied(ctx, null, null, ErrorCodes.RATE_LIMITED);
            throw rateLimited; // → 429 problem+json with Retry-After
        }

        // 1) Global kill switch first — a disabled gateway reveals nothing about
        //    which slugs exist, and it outranks any per-VM opt-in.
        if (!settingsService.bool(SettingsService.SSH_GATEWAY_ENABLED, false)) {
            return deny(ctx, null, null, RouteOutcome.forbidden(ErrorCodes.SSHGW_GATEWAY_DISABLED));
        }

        // 2) Slug → VM.
        Optional<Vm> found = vmRepository.findByHostname(ctx.slug());
        if (found.isEmpty()) {
            return deny(ctx, null, null, RouteOutcome.notFound(ErrorCodes.SSHGW_ROUTE_NOT_FOUND));
        }
        Vm vm = found.get();

        // 3) RUNNING, 4) not per-VM blocked.
        if (vm.getStatus() != VmStatus.RUNNING) {
            return deny(ctx, vm.getId(), null, RouteOutcome.forbidden(ErrorCodes.SSHGW_VM_NOT_RUNNING));
        }
        if (vm.isSshGatewayBlocked()) {
            return deny(ctx, vm.getId(), null, RouteOutcome.forbidden(ErrorCodes.SSHGW_VM_BLOCKED));
        }

        // 5) Identity: publickey (fingerprint → key → member) or password opt-in.
        UserSshKey identifiedKey = null;
        if (RouteRequest.AUTH_PUBLICKEY.equals(ctx.authMethod())) {
            Optional<UserSshKey> key = ctx.fingerprint() == null || ctx.fingerprint().isBlank()
                    ? Optional.empty()
                    : sshKeyRepository.findByFingerprintSha256(ctx.fingerprint());
            if (key.isEmpty()) {
                // Not yet identified → actor stays null.
                return deny(ctx, vm.getId(), null,
                        RouteOutcome.forbidden(ErrorCodes.SSHGW_KEY_UNKNOWN));
            }
            identifiedKey = key.get();
            ctx.identify(identifiedKey);
            GroupMemberRole role = groupMemberRepository
                    .findByGroupIdAndUserId(vm.getGroupId(), identifiedKey.getUserId())
                    .map(GroupMember::getRole)
                    .orElse(null);
            // VIEWER and non-members are denied identically (one code, no oracle).
            if (role == null || !role.atLeast(GroupMemberRole.MEMBER)) {
                return deny(ctx, vm.getId(), identifiedKey.getUserId(),
                        RouteOutcome.forbidden(ErrorCodes.SSHGW_KEY_NOT_MEMBER));
            }
        } else if (RouteRequest.AUTH_PASSWORD.equals(ctx.authMethod())) {
            if (!vmSettingsService.bool(vm.getId(), VmSettingsService.SSH_PASSWORD_ENABLED)) {
                return deny(ctx, vm.getId(), null,
                        RouteOutcome.forbidden(ErrorCodes.SSHGW_PASSWORD_DISABLED));
            }
        } else {
            // Unknown method — fail closed, unidentified.
            return deny(ctx, vm.getId(), null, RouteOutcome.forbidden(ErrorCodes.SSHGW_KEY_UNKNOWN));
        }

        // 6) A collected host key to pin. actor = identified user (publickey) or null.
        if (vm.getSshHostKey() == null || vm.getSshHostKey().isBlank()) {
            return deny(ctx, vm.getId(), ctx.identifiedUserId(),
                    RouteOutcome.forbidden(ErrorCodes.SSHGW_NO_HOST_KEY));
        }

        // 7) A live IP allocation (SSRF-safe: owned + ALLOCATED guard).
        String ip = ipAddressResolver.liveHostIp(vm.getIpAllocationId(), vm.getId());
        if (ip == null) {
            return deny(ctx, vm.getId(), ctx.identifiedUserId(),
                    RouteOutcome.forbidden(ErrorCodes.SSHGW_ROUTE_NO_ADDRESS));
        }

        RouteResponse route = new RouteResponse(ip, UPSTREAM_SSH_PORT, vm.getSshUsername(),
                List.of(vm.getSshHostKey()));
        auditService.record(ctx.identifiedUserId(), AuditService.ACTOR_ROLE_SSHGW,
                AuditService.SSHGW_ROUTE, "vm", vm.getId(), ctx.detail(null), ctx.sourceIp());
        if (identifiedKey != null) {
            touchLastUsed(identifiedKey.getId());
        }
        return RouteOutcome.granted(route);
    }

    /** Best-effort — a failed last_used_at bump must not fail the route. */
    private void touchLastUsed(Long keyId) {
        try {
            sshKeyRepository.touchLastUsedAt(keyId, Instant.now());
        } catch (RuntimeException e) {
            log.debug("sshgw route: last_used_at bump failed for key {} (ignored)", keyId, e);
        }
    }

    private RouteOutcome deny(Context ctx, Long vmId, Long actorId, RouteOutcome outcome) {
        auditDenied(ctx, vmId, actorId, outcome.reason());
        return outcome;
    }

    private void auditDenied(Context ctx, Long vmId, Long actorId, String reason) {
        auditService.record(actorId, AuditService.ACTOR_ROLE_SSHGW, AuditService.SSHGW_ROUTE_DENIED,
                "vm", vmId, ctx.detail(reason), ctx.sourceIp());
    }

    /** Per-request scratch: request fields plus the identity resolved so far. */
    private static final class Context {
        private final RouteRequest request;
        private final String gatewayPeer;
        private Long identifiedUserId;
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
            this.identifiedUserId = key.getUserId();
            this.identifiedKeyId = key.getId();
        }

        Long identifiedUserId() {
            return identifiedUserId;
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
