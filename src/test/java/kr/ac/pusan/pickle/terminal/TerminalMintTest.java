package kr.ac.pusan.pickle.terminal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Web-terminal ticket mint gate chain (contract {@code createTerminalSession}):
 * kill switch (503) → visible VM + MEMBER+ (404 mask for
 * non-member/VIEWER) → RUNNING (409) → per-VM admin block (403) → dual-key rate
 * limit (429) → concurrent cap (409), then a 201 with a no-store one-time ticket.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class TerminalMintTest {

    private static final AtomicInteger VMID_SEQ = new AtomicInteger(970_000);
    private static final int RATE_LIMIT = 5;

    @DynamicPropertySource
    static void terminalProperties(DynamicPropertyRegistry registry) {
        registry.add("pickle.terminal.rate-limit-per-minute", () -> RATE_LIMIT);
        registry.add("pickle.terminal.per-user-cap", () -> 3);
        // VM/org caps kept high so only the per-user cap trips deterministically.
        registry.add("pickle.terminal.per-vm-cap", () -> 100);
        registry.add("pickle.terminal.per-org-cap", () -> 100);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private TerminalSessionRegistry sessionRegistry;
    @Autowired
    private RateLimitService rateLimitService;

    private User member;
    private User viewer;
    private User outsider;
    private String memberToken;
    private String viewerToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long templateId;
    private long groupId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'terminal_mint%'");
        sessionRegistry.all().forEach(s -> sessionRegistry.remove(s.sessionId()));
        setWebTerminalEnabled(true);

        member = ensureUser("term.member@pusan.ac.kr", "터미널멤버");
        viewer = ensureUser("term.viewer@pusan.ac.kr", "터미널뷰어");
        outsider = ensureUser("term.outsider@pusan.ac.kr", "터미널외부인");
        memberToken = jwtService.createAccessToken(member);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = jdbcTemplate.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        groupId = createGroup();
        addMember(groupId, member.getId(), "MEMBER");
        addMember(groupId, viewer.getId(), "VIEWER");
    }

    @Test
    void memberMintsTicketWithNoStoreAndFixedSubprotocol() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, false);
        mint(memberToken, vmId)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andExpect(jsonPath("$.wsPath").value("/terminal/ws"))
                .andExpect(jsonPath("$.subprotocol").value("pickle.terminal.v1"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void killSwitchOffReturns503() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, false);
        setWebTerminalEnabled(false);
        mint(memberToken, vmId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TERMINAL_DISABLED"));
    }

    @Test
    void nonMemberIsMasked404ButViewerGets403() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, false);
        // non-member: existence masked as 404.
        mint(outsiderToken, vmId).andExpect(status().isNotFound());
        // VIEWER already sees the VM (getVm), so it gets an honest role 403.
        mint(viewerToken, vmId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
    }

    @Test
    void stoppedVmReturns409() throws Exception {
        long vmId = createVm(VmStatus.STOPPED, false);
        mint(memberToken, vmId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
    }

    @Test
    void adminBlockedVmReturns403() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, true);
        mint(memberToken, vmId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void concurrentCapReturns409() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, false);
        // fill the per-user cap (3) with reported mirror sessions, then mint once.
        for (int i = 0; i < 3; i++) {
            sessionRegistry.registerPending("cap-" + UUID.randomUUID(), member.getId(),
                    UserRole.USER, vmId, orgId);
        }
        mint(memberToken, vmId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMINAL_SESSION_LIMIT"));
    }

    @Test
    void rateLimitOnUserKeyReturns429() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, false);
        // pre-fill the userId-keyed budget to the limit; the mint's own hit trips it.
        for (int i = 0; i < RATE_LIMIT; i++) {
            rateLimitService.hit(TerminalService.RATE_LIMIT_SCOPE_USER,
                    String.valueOf(member.getId()), RATE_LIMIT);
        }
        mint(memberToken, vmId)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResultActions mint(String token, long vmId) throws Exception {
        return mockMvc.perform(post("/api/v1/vms/" + vmId + "/terminal-sessions")
                .header("Authorization", "Bearer " + token));
    }

    private void setWebTerminalEnabled(boolean enabled) {
        jdbcTemplate.update("update settings set value = ?::jsonb where key = 'web_terminal_enabled'",
                String.valueOf(enabled));
    }

    private long createGroup() {
        return jdbcTemplate.queryForObject("""
                insert into groups (kind, name, slug)
                values ('TEAM'::group_kind, '터미널팀', ?) returning id
                """, Long.class, "term-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void addMember(long groupId, long userId, String role) {
        jdbcTemplate.update("""
                insert into group_members (group_id, user_id, role)
                values (?, ?, ?::group_member_role)
                on conflict (group_id, user_id) do update set role = excluded.role
                """, groupId, userId, role);
    }

    private long createVm(VmStatus status, boolean blocked) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '터미널 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, member.getId(), templateId);
        String hostname = "term-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status,
                                 ssh_gateway_blocked)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, ?::vm_status, ?)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, VMID_SEQ.incrementAndGet(), status.name(), blocked);
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
