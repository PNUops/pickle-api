package kr.ac.pusan.pickle.terminal;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToUser;
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
import kr.ac.pusan.pickle.support.SeedFixtures;
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
 * kill switch (503) → visible VM + MEMBER+ on the VM's access list (404 mask
 * for an outsider, 403 for a grantee below MEMBER) → RUNNING (409) → per-VM
 * admin block (403) → dual-key rate limit (429) → concurrent cap (409), then a
 * 201 with a no-store one-time ticket.
 *
 * <p>Opening a terminal is one of the things standing in the owning workspace never
 * buys, so both fixture users hold a real grant on each VM: one at MEMBER, one
 * at VIEWER.
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
    private long imageId;
    private long workspaceId;

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
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        workspaceId = createWorkspace();
        // Both belong to the owning workspace; what separates them is the rung each
        // one is given on the VM itself (see createVm).
        addMember(workspaceId, member.getId(), "MEMBER");
        addMember(workspaceId, viewer.getId(), "MEMBER");
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
        // a VIEWER grantee already sees the VM (getVm), so it gets an honest
        // role 403.
        mint(viewerToken, vmId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ROLE_INSUFFICIENT"));
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
                    UserRole.USER, vmId, orgId, 0);
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

    private long createWorkspace() {
        return jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name)
                values ('TEAM'::workspace_kind, '터미널팀') returning id
                """, Long.class);
    }

    private void addMember(long workspaceId, long userId, String role) {
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, ?::workspace_member_role)
                on conflict (workspace_id, user_id) do update set role = excluded.role
                """, workspaceId, userId, role);
    }

    /**
     * A VM inserted this way never passes approval, so it starts with an empty
     * access list; the two fixture rungs are written on it here.
     */
    private long createVm(VmStatus status, boolean blocked) {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, member.getId(), "터미널 테스트", imageId, 1, 1024, 10);
        String hostname = "term-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status,
                                 ssh_gateway_blocked)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, ?::vm_status, ?)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, VMID_SEQ.incrementAndGet(), status.name(), blocked);
        grantVmToUser(jdbcTemplate, vmId, member.getId(), "MEMBER");
        grantVmToUser(jdbcTemplate, vmId, viewer.getId(), "VIEWER");
        return vmId;
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
