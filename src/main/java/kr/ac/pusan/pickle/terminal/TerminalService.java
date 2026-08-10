package kr.ac.pusan.pickle.terminal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.VmAccessService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.config.TerminalProperties;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.ipam.IpAddressResolver;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.sshgw.SshGatewayRouteService;
import kr.ac.pusan.pickle.terminal.TerminalSessionRegistry.MirrorSession;
import kr.ac.pusan.pickle.terminal.dto.TerminalRedeemResponse;
import kr.ac.pusan.pickle.terminal.dto.TerminalRevalidateResponse;
import kr.ac.pusan.pickle.terminal.dto.TerminalSessionEndRequest;
import kr.ac.pusan.pickle.terminal.dto.TerminalSessionView;
import kr.ac.pusan.pickle.terminal.dto.TerminalTicketResponse;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Web-terminal control plane (the internal web-terminal contract). pickle-api
 * mints one-time tickets, answers the bridge's redeem /
 * session / revalidate calls, mirrors reported sessions for the admin view, and
 * force-terminates via the bridge control port. It never touches terminal bytes.
 *
 * <p>The mint gate order is normative (encodes precedence <b>kill switch &gt;
 * per-VM admin block &gt; membership</b>): kill switch → visible VM + MEMBER+
 * (404 masking) → RUNNING → per-VM block → dual-key rate limit → concurrent cap.
 * Redeem re-checks the same authorization (the mint decision may be up to the
 * ticket TTL stale) and, on the allow path, resolves the live IP and pinned host
 * keys for the bridge.</p>
 */
@Service
public class TerminalService {

    /** Dual-key mint rate limit (independent-revocation key path): IP is spoofable, userId is not. */
    static final String RATE_LIMIT_SCOPE_IP = "terminal_mint_ip";
    static final String RATE_LIMIT_SCOPE_USER = "terminal_mint_user";
    private static final int SSH_PORT = 22;

    private final SettingsService settingsService;
    private final VmRepository vmRepository;
    private final VmAccessService vmAccessService;
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final IpAddressResolver ipAddressResolver;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final TicketRegistry ticketRegistry;
    private final TerminalSessionRegistry sessionRegistry;
    private final BridgeControlClient bridgeControlClient;
    private final TerminalProperties properties;

    public TerminalService(SettingsService settingsService, VmRepository vmRepository,
            VmAccessService vmAccessService, WorkspaceRepository workspaceRepository,
            OrgRepository orgRepository, UserRepository userRepository,
            IpAddressResolver ipAddressResolver, RateLimitService rateLimitService,
            AuditService auditService, TicketRegistry ticketRegistry,
            TerminalSessionRegistry sessionRegistry, BridgeControlClient bridgeControlClient,
            TerminalProperties properties) {
        this.settingsService = settingsService;
        this.vmRepository = vmRepository;
        this.vmAccessService = vmAccessService;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.ipAddressResolver = ipAddressResolver;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.ticketRegistry = ticketRegistry;
        this.sessionRegistry = sessionRegistry;
        this.bridgeControlClient = bridgeControlClient;
        this.properties = properties;
    }

    // ── public mint (POST /vms/{vmId}/terminal-sessions) ──────────────────────

    /**
     * Mints a one-time ticket for the caller on the given VM. Runs the normative
     * gate chain (see class javadoc). No {@code @PreAuthorize}: authorization is
     * service-layer here (existence-masking 404 for non-members).
     */
    @Transactional(readOnly = true)
    public TerminalTicketResponse mint(AuthenticatedUser actor, UUID publicVmId, String clientIp) {
        // 1) global kill switch first (a disabled feature reveals nothing).
        if (!settingsService.bool(SettingsService.WEB_TERMINAL_ENABLED, false)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.TERMINAL_DISABLED,
                    "웹 터미널을 사용할 수 없습니다", "웹 터미널 기능이 현재 비활성화되어 있습니다.");
        }
        // 2) visible VM + workspace MEMBER+. A non-member (or missing VM) is masked as
        //    404 (existence stays private); a VIEWER already sees the VM via getVm,
        //    so it gets an honest 403 (same as the power-control paths) rather than
        //    a misleading 404.
        Vm vm = vmRepository.findByPublicId(publicVmId).orElseThrow(VmAccessService::vmNotFound);
        vmAccessService.of(vm, actor.id()).requireAtLeast(ResourceRole.MEMBER,
                "웹 터미널을 열 권한이 없습니다", "이 VM의 참여자 이상만 웹 터미널을 사용할 수 있습니다.");
        // 3) RUNNING.
        if (vm.getStatus() != VmStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "현재 상태에서는 수행할 수 없는 작업입니다",
                    "실행 중인 VM에서만 웹 터미널을 열 수 있습니다.");
        }
        // 4) per-VM admin block (same flag the SSH gateway reads).
        if (vm.isSshGatewayBlocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근이 차단된 VM입니다", "관리자가 이 VM의 원격 접속을 차단했습니다. 관리자에게 문의하세요.");
        }
        // 5) dual-key rate limit (either key over budget → 429).
        int limit = properties.rateLimitPerMinute();
        rateLimitService.hit(RATE_LIMIT_SCOPE_IP, clientIp, limit);
        rateLimitService.hit(RATE_LIMIT_SCOPE_USER, String.valueOf(actor.id()), limit);
        // 6) concurrent-session cap over tickets + mirror (a minted ticket is a
        //    reserved slot, so double-mint abuse in the 60s window is bounded).
        enforceCaps(actor.id(), vm.getId(), vm.getOrgId());

        // 7) capture the session's account token version. A password change or
        //    reset bumps it, and the shared redeem/revalidate gate refuses anything
        //    minted under an older value — that is what ends a live terminal.
        int tokenVersion = userRepository.findById(actor.id())
                .map(User::getTokenVersion)
                .orElseThrow(TerminalService::sessionUserGone);

        String sessionId = UUID.randomUUID().toString();
        TicketRegistry.Minted minted = ticketRegistry.mint(sessionId, actor.id(), vm.getId(),
                vm.getOrgId(), actor.role(), tokenVersion);
        return TerminalTicketResponse.of(sessionId, minted.ticket(), minted.expiresAt());
    }

    private void enforceCaps(long userId, long vmId, long orgId) {
        // reclaim any leaked mirror slots before counting, so a dead bridge cannot
        // permanently exhaust a cap.
        sessionRegistry.prune();
        long userCount = ticketRegistry.countUser(userId) + sessionRegistry.countUser(userId);
        long vmCount = ticketRegistry.countVm(vmId) + sessionRegistry.countVm(vmId);
        long orgCount = ticketRegistry.countOrg(orgId) + sessionRegistry.countOrg(orgId);
        if (userCount >= properties.perUserCap() || vmCount >= properties.perVmCap()
                || orgCount >= properties.perOrgCap()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TERMINAL_SESSION_LIMIT,
                    "동시 터미널 세션 상한을 초과했습니다", "사용 중인 터미널 세션을 닫은 뒤 다시 시도해 주세요.");
        }
    }

    // ── internal: redeem (POST /internal/terminal/redeem) ─────────────────────

    /** Exactly one of {@code response}/{@code reason} is non-null. */
    public record RedeemOutcome(TerminalRedeemResponse response, String reason) {

        static RedeemOutcome granted(TerminalRedeemResponse r) {
            return new RedeemOutcome(r, null);
        }

        static RedeemOutcome denied(String reason) {
            return new RedeemOutcome(null, reason);
        }

        public boolean granted() {
            return response != null;
        }
    }

    /**
     * Atomically consumes the ticket, re-checks authorization, and — on allow —
     * registers a PENDING mirror entry and returns the connection facts. Deny
     * writes nothing user-attributed (unauthenticated caller path).
     */
    @Transactional(readOnly = true)
    public RedeemOutcome redeem(String ticket) {
        Optional<TicketRegistry.Ticket> consumed = ticketRegistry.redeem(ticket);
        if (consumed.isEmpty()) {
            return RedeemOutcome.denied(TerminalReasons.TICKET_INVALID);
        }
        TicketRegistry.Ticket t = consumed.get();
        Authz authz = authorize(t.vmId(), t.userId(), t.tokenVersion());
        if (!authz.allowed()) {
            return RedeemOutcome.denied(authz.reason());
        }
        Vm vm = authz.vm();
        String vmIp = ipAddressResolver.liveHostIp(vm.getIpAllocationId(), vm.getId());
        if (vmIp == null) {
            // A RUNNING VM with no live IP is unreachable — fail closed as VM_NOT_RUNNING.
            return RedeemOutcome.denied(TerminalReasons.VM_NOT_RUNNING);
        }
        // hostKeys may be empty; the bridge fail-closes (WS 4006) on an empty pin
        // set, so the api returns 200 and lets the bridge own that refusal.
        List<String> hostKeys = SshGatewayRouteService.splitHostKeys(vm.getSshHostKey());
        sessionRegistry.registerPending(t.sessionId(), t.userId(), t.userRole(), vm.getId(),
                vm.getOrgId(), t.tokenVersion());
        return RedeemOutcome.granted(new TerminalRedeemResponse(t.sessionId(), t.userId(),
                vm.getId(), vmIp, SSH_PORT, vm.getSshUsername(), hostKeys));
    }

    // ── internal: session-start / session-end ─────────────────────────────────

    /**
     * Marks the session live and audits {@code terminal.session_start}. Throws 409
     * when the session was never redeemed or already started (bridge closes WS —
     * inconsistent state).
     */
    @Transactional
    public void sessionStart(String sessionId, String clientIp) {
        if (!sessionRegistry.markStarted(sessionId, clientIp)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_INVALID_STATE,
                    "세션 상태가 올바르지 않습니다", "존재하지 않거나 이미 시작된 세션입니다.");
        }
        MirrorSession s = sessionRegistry.get(sessionId).orElseThrow();
        // detail carries lifecycle metadata ONLY — never frame/keystroke content.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sessionId", sessionId);
        detail.put("vmId", s.vmId());
        detail.put("clientIp", clientIp);
        auditService.record(s.userId(), roleName(s.userRole()), AuditService.TERMINAL_SESSION_START,
                "vm", vmPublicId(s.vmId()), detail, clientIp);
    }

    /**
     * Removes the mirror entry and audits {@code terminal.session_end}. Idempotent:
     * a repeated or unknown sessionId is a no-op (no audit), so bridge retries are
     * safe.
     */
    @Transactional
    public void sessionEnd(TerminalSessionEndRequest request) {
        Optional<MirrorSession> removed = sessionRegistry.remove(request.sessionId());
        if (removed.isEmpty()) {
            return; // idempotent no-op
        }
        MirrorSession s = removed.get();
        // detail carries counts/reasons ONLY — never frame/keystroke content.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sessionId", request.sessionId());
        detail.put("vmId", s.vmId());
        detail.put("reason", request.reason());
        detail.put("durationSeconds", request.durationSeconds());
        detail.put("bytesIn", request.bytesIn());
        detail.put("bytesOut", request.bytesOut());
        auditService.record(s.userId(), roleName(s.userRole()), AuditService.TERMINAL_SESSION_END,
                "vm", vmPublicId(s.vmId()), detail, s.clientIp());
    }

    // ── internal: revalidate ──────────────────────────────────────────────────

    /**
     * Re-checks a live session (polled every 60s). {@code SESSION_UNKNOWN} when the
     * mirror lost it (api restart) → bridge closes WS 1001; otherwise the same
     * authorization gate as redeem.
     */
    @Transactional(readOnly = true)
    public TerminalRevalidateResponse revalidate(String sessionId) {
        Optional<MirrorSession> s = sessionRegistry.get(sessionId);
        if (s.isEmpty()) {
            return TerminalRevalidateResponse.denied(TerminalReasons.SESSION_UNKNOWN);
        }
        // the 60s poll is the session heartbeat — refresh liveness so the leak
        // pruner never evicts a genuinely-live session.
        sessionRegistry.touch(sessionId);
        Authz authz = authorize(s.get().vmId(), s.get().userId(), s.get().tokenVersion());
        return authz.allowed() ? TerminalRevalidateResponse.allowed()
                : TerminalRevalidateResponse.denied(authz.reason());
    }

    // ── admin: list / terminate ───────────────────────────────────────────────

    /**
     * Live sessions (started only), newest first. ORG tier sees only sessions on
     * VMs owned by their org; SYS tier sees all.
     */
    @Transactional(readOnly = true)
    public List<TerminalSessionView> list(AuthenticatedUser actor) {
        sessionRegistry.prune();
        List<MirrorSession> sessions = sessionRegistry.started();
        if (actor.role().isOrgTier()) {
            Long orgId = actor.orgId();
            sessions = sessions.stream().filter(s -> orgId != null && s.orgId() == orgId).toList();
        }
        if (sessions.isEmpty()) {
            return List.of();
        }
        Map<Long, Vm> vms = vmRepository.findAllById(
                        sessions.stream().map(MirrorSession::vmId).distinct().toList())
                .stream().collect(Collectors.toMap(Vm::getId, v -> v));
        Map<Long, User> users = userRepository.findAllById(
                        sessions.stream().map(MirrorSession::userId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));
        Map<Long, String> workspaceNames = workspaceRepository.findAllById(
                        vms.values().stream().map(Vm::getWorkspaceId).distinct().toList())
                .stream().collect(Collectors.toMap(Workspace::getId, Workspace::getName));
        Map<Long, Org> orgs = orgRepository.findAllById(
                        sessions.stream().map(MirrorSession::orgId).distinct().toList())
                .stream().collect(Collectors.toMap(Org::getId, o -> o));
        List<TerminalSessionView> views = new ArrayList<>(sessions.size());
        for (MirrorSession s : sessions) {
            // The mirror holds the internal ids the gateway contract speaks; the
            // admin view reports the same rows by their public identifiers.
            Vm vm = vms.get(s.vmId());
            User user = users.get(s.userId());
            Org org = orgs.get(s.orgId());
            views.add(new TerminalSessionView(
                    s.sessionId(), vm != null ? vm.getPublicId() : null,
                    vm != null ? vm.getName() : "", org != null ? org.getPublicId() : null,
                    org != null ? org.getName() : "",
                    vm != null ? workspaceNames.getOrDefault(vm.getWorkspaceId(), "") : "",
                    user != null ? user.getPublicId() : null,
                    user != null ? user.getEmail() : "",
                    user != null ? user.getName() : "", s.clientIp(), s.startedAt()));
        }
        return views;
    }

    /**
     * Force-terminates a session (SYS_ADMIN only, gated at the controller). Always
     * relays to the bridge (idempotent); audits {@code terminal.force_terminate}
     * only when the session is known to the mirror (an unknown/already-ended id is
     * a 204 no-op with no audit).
     */
    @Transactional
    public void terminate(AuthenticatedUser actor, String sessionId, String ip) {
        Optional<MirrorSession> known = sessionRegistry.get(sessionId);
        bridgeControlClient.terminate(sessionId);
        if (known.isPresent()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sessionId", sessionId);
            detail.put("vmId", known.get().vmId());
            auditService.record(actor.id(), actor.role().name(),
                    AuditService.TERMINAL_FORCE_TERMINATE, "vm", vmPublicId(known.get().vmId()),
                    detail, ip);
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Authorization re-check shared by redeem and revalidate. {@code tokenVersion}
     * is the value captured when the ticket was minted; a mismatch means the
     * account's sessions were invalidated in the meantime (password change or
     * reset, withdrawal), so the terminal is revoked like any other session — the
     * bridge polls every 60 seconds, which bounds how long a live session outlives
     * the password change.
     */
    private Authz authorize(long vmId, long userId, int tokenVersion) {
        if (!settingsService.bool(SettingsService.WEB_TERMINAL_ENABLED, false)) {
            return Authz.deny(TerminalReasons.TERMINAL_DISABLED);
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            return Authz.deny(TerminalReasons.ACCESS_REVOKED);
        }
        if (user.getTokenVersion() != tokenVersion) {
            return Authz.deny(TerminalReasons.ACCESS_REVOKED);
        }
        Vm vm = vmRepository.findById(vmId).orElse(null);
        if (vm == null || vm.getStatus() != VmStatus.RUNNING) {
            return Authz.deny(TerminalReasons.VM_NOT_RUNNING);
        }
        if (vm.isSshGatewayBlocked()) {
            return Authz.deny(TerminalReasons.ACCESS_REVOKED);
        }
        if (!vmAccessService.of(vm, userId).atLeast(ResourceRole.MEMBER)) {
            return Authz.deny(TerminalReasons.ACCESS_REVOKED);
        }
        return Authz.allow(vm);
    }

    private record Authz(String reason, Vm vm) {
        static Authz deny(String reason) {
            return new Authz(reason, null);
        }

        static Authz allow(Vm vm) {
            return new Authz(null, vm);
        }

        boolean allowed() {
            return reason == null;
        }
    }

    private static String roleName(UserRole role) {
        return role != null ? role.name() : null;
    }

    private static ApiException sessionUserGone() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_TOKEN_INVALID,
                "인증이 필요합니다", "액세스 토큰이 없거나 만료되었습니다. 토큰을 갱신한 뒤 다시 시도해 주세요.");
    }

    /**
     * The VM's public identifier for the audit trail. The gateway contract
     * itself stays on the internal id (a Go client decodes it as int64), so
     * the translation happens here, at the audit boundary.
     */
    private UUID vmPublicId(Long vmId) {
        return vmId == null ? null
                : vmRepository.findById(vmId).map(Vm::getPublicId).orElse(null);
    }
}
