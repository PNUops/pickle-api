package kr.ac.pusan.pickle.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal terminal control link, bridge → pickle-api:
 * atomic single-use redeem with authorization re-check, session lifecycle
 * audit (proving <b>no frame content</b> ever reaches the detail map), the 60s
 * revalidation poll, and the shared {@code /internal/**} auth (bearer + source).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InternalTerminalTest {

    private static final String TOKEN = "test-sshgw-token";
    private static final String SSHGW_IP = "172.30.1.30";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String HOST_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITerminalHostKeyFixtureForRedeemTestsAAA";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TicketRegistry ticketRegistry;
    @Autowired
    private TerminalSessionRegistry sessionRegistry;

    private long orgId;
    private long imageId;
    private long nodeId;
    private long poolId;
    private long groupId;
    private User member;

    @BeforeEach
    void setUp() {
        setWebTerminalEnabled(true);
        sessionRegistry.all().forEach(s -> sessionRegistry.remove(s.sessionId()));
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        poolId = jdbcTemplate.queryForObject("select id from ip_pools where name = 'guest-private'",
                Long.class);
        nodeId = ensureNode();
        member = ensureUser("term.redeem.member@pusan.ac.kr", "리딤멤버");
        groupId = createGroup();
        addMember(groupId, member.getId(), "MEMBER");
    }

    // ── redeem ─────────────────────────────────────────────────────────────

    @Test
    void redeemHappyPathReturnsRouteAndRegistersPending() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.11", false, HOST_KEY);
        String sessionId = UUID.randomUUID().toString();
        String ticket = mintTicket(sessionId, vmId);

        redeem(ticket, SSHGW_IP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.userId").value(member.getId()))
                .andExpect(jsonPath("$.vmId").value((int) vmId))
                .andExpect(jsonPath("$.vmIp").value("172.29.5.11"))
                .andExpect(jsonPath("$.port").value(22))
                .andExpect(jsonPath("$.user").value("ubuntu"))
                .andExpect(jsonPath("$.hostKeys[0]").value(HOST_KEY));

        assertThat(sessionRegistry.get(sessionId)).isPresent();
    }

    @Test
    void redeemIsSingleUse() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.12", false, HOST_KEY);
        String ticket = mintTicket(UUID.randomUUID().toString(), vmId);
        redeem(ticket, SSHGW_IP).andExpect(status().isOk());
        redeem(ticket, SSHGW_IP)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TICKET_INVALID"));
    }

    @Test
    void unknownTicketIsTicketInvalid() throws Exception {
        redeem("no-such-ticket", SSHGW_IP)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TICKET_INVALID"));
    }

    @Test
    void redeemReCheckMembershipRemovedIsAccessRevoked() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.13", false, HOST_KEY);
        String ticket = mintTicket(UUID.randomUUID().toString(), vmId);
        jdbcTemplate.update("delete from group_members where group_id = ? and user_id = ?",
                groupId, member.getId());
        redeem(ticket, SSHGW_IP)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("ACCESS_REVOKED"));
    }

    @Test
    void redeemReCheckVmStoppedIsVmNotRunning() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.14", false, HOST_KEY);
        String ticket = mintTicket(UUID.randomUUID().toString(), vmId);
        jdbcTemplate.update("update vms set status = 'STOPPED'::vm_status where id = ?", vmId);
        redeem(ticket, SSHGW_IP)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("VM_NOT_RUNNING"));
    }

    @Test
    void redeemReCheckKillSwitchOffIsTerminalDisabled() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.15", false, HOST_KEY);
        String ticket = mintTicket(UUID.randomUUID().toString(), vmId);
        setWebTerminalEnabled(false);
        redeem(ticket, SSHGW_IP)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TERMINAL_DISABLED"));
    }

    @Test
    void redeemReCheckAdminBlockIsAccessRevoked() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.16", false, HOST_KEY);
        String ticket = mintTicket(UUID.randomUUID().toString(), vmId);
        jdbcTemplate.update("update vms set ssh_gateway_blocked = true where id = ?", vmId);
        redeem(ticket, SSHGW_IP)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("ACCESS_REVOKED"));
    }

    @Test
    void redeemAfterPasswordChangeIsAccessRevoked() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.18", false, HOST_KEY);
        String ticket = mintTicket(UUID.randomUUID().toString(), vmId);
        // a password change or reset bumps token_version, invalidating every session
        // of the account — including a ticket already handed out.
        bumpTokenVersion();
        redeem(ticket, SSHGW_IP)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("ACCESS_REVOKED"));
    }

    @Test
    void revalidateAfterPasswordChangeRevokesTheLiveSession() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.19", false, HOST_KEY);
        String sessionId = UUID.randomUUID().toString();
        redeem(mintTicket(sessionId, vmId), SSHGW_IP).andExpect(status().isOk());
        internal("/internal/terminal/session-start", SSHGW_IP,
                Map.of("sessionId", sessionId, "clientIp", CLIENT_IP))
                .andExpect(status().isNoContent());
        internal("/internal/terminal/revalidate", SSHGW_IP, Map.of("sessionId", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allow").value(true));

        bumpTokenVersion();

        // the next 60s poll ends the in-progress session
        internal("/internal/terminal/revalidate", SSHGW_IP, Map.of("sessionId", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allow").value(false))
                .andExpect(jsonPath("$.reason").value("ACCESS_REVOKED"));
    }

    @Test
    void redeemWithNoHostKeyStillReturns200WithEmptyArray() throws Exception {
        // A VM whose host key was never collected redeems with an EMPTY hostKeys
        // array (200) — the bridge owns the fail-closed refusal (WS 4006), not api.
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.17", false, null);
        String ticket = mintTicket(UUID.randomUUID().toString(), vmId);
        redeem(ticket, SSHGW_IP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostKeys.length()").value(0));
    }

    // ── session-start / session-end (audit, no content) ──────────────────────

    @Test
    void sessionStartAuditsLifecycleOnlyThenConflictsOnRepeat() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.20", false, HOST_KEY);
        String sessionId = UUID.randomUUID().toString();
        sessionRegistry.registerPending(sessionId, member.getId(), UserRole.USER, vmId, orgId,
                member.getTokenVersion());

        internal("/internal/terminal/session-start", SSHGW_IP,
                Map.of("sessionId", sessionId, "clientIp", CLIENT_IP))
                .andExpect(status().isNoContent());

        // audited terminal.session_start with EXACTLY the lifecycle keys — proving
        // no frame/keystroke content field can exist.
        assertThat(detailKeys("terminal.session_start", vmId))
                .containsExactlyInAnyOrder("sessionId", "vmId", "clientIp");

        // a second start on the now-started session is an inconsistent-state 409.
        internal("/internal/terminal/session-start", SSHGW_IP,
                Map.of("sessionId", sessionId, "clientIp", CLIENT_IP))
                .andExpect(status().isConflict());
    }

    @Test
    void sessionStartOnUnknownSessionConflicts() throws Exception {
        internal("/internal/terminal/session-start", SSHGW_IP,
                Map.of("sessionId", UUID.randomUUID().toString(), "clientIp", CLIENT_IP))
                .andExpect(status().isConflict());
    }

    @Test
    void sessionEndAuditsCountsOnlyAndIsIdempotent() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.21", false, HOST_KEY);
        String sessionId = UUID.randomUUID().toString();
        sessionRegistry.registerPending(sessionId, member.getId(), UserRole.USER, vmId, orgId,
                member.getTokenVersion());
        sessionRegistry.markStarted(sessionId, CLIENT_IP);

        Map<String, Object> body = Map.of("sessionId", sessionId, "reason", "CLIENT_CLOSED",
                "durationSeconds", 812, "bytesIn", 10432, "bytesOut", 583201);
        internal("/internal/terminal/session-end", SSHGW_IP, body).andExpect(status().isNoContent());

        assertThat(detailKeys("terminal.session_end", vmId))
                .containsExactlyInAnyOrder("sessionId", "vmId", "reason", "durationSeconds",
                        "bytesIn", "bytesOut");

        // idempotent: a repeated end on the now-removed session is a 204 no-op with
        // no second audit row.
        internal("/internal/terminal/session-end", SSHGW_IP, body).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'terminal.session_end' "
                        + "and target_id = ?", Long.class, vmId)).isEqualTo(1L);
    }

    // ── revalidate ────────────────────────────────────────────────────────────

    @Test
    void revalidateAllowsThenDeniesOnKillSwitchAndUnknown() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, "172.29.5.22", false, HOST_KEY);
        String sessionId = UUID.randomUUID().toString();
        sessionRegistry.registerPending(sessionId, member.getId(), UserRole.USER, vmId, orgId,
                member.getTokenVersion());
        sessionRegistry.markStarted(sessionId, CLIENT_IP);

        internal("/internal/terminal/revalidate", SSHGW_IP, Map.of("sessionId", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allow").value(true));

        setWebTerminalEnabled(false);
        internal("/internal/terminal/revalidate", SSHGW_IP, Map.of("sessionId", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allow").value(false))
                .andExpect(jsonPath("$.reason").value("TERMINAL_DISABLED"));

        internal("/internal/terminal/revalidate", SSHGW_IP,
                Map.of("sessionId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allow").value(false))
                .andExpect(jsonPath("$.reason").value("SESSION_UNKNOWN"));
    }

    // ── internal auth (bearer + source) ────────────────────────────────────────

    @Test
    void wrongTokenIsRejectedWith401() throws Exception {
        mockMvc.perform(internalRequest("/internal/terminal/redeem", SSHGW_IP,
                        Map.of("ticket", "x")).header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void wrongSourceIpIsRejectedWith403() throws Exception {
        internal("/internal/terminal/redeem", "172.30.1.99", Map.of("ticket", "x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String mintTicket(String sessionId, long vmId) {
        return ticketRegistry.mint(sessionId, member.getId(), vmId, orgId, UserRole.USER,
                member.getTokenVersion()).ticket();
    }

    private ResultActions redeem(String ticket, String peerIp) throws Exception {
        return internal("/internal/terminal/redeem", peerIp, Map.of("ticket", ticket));
    }

    private ResultActions internal(String path, String peerIp, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(internalRequest(path, peerIp, body)
                .header("Authorization", "Bearer " + TOKEN));
    }

    private MockHttpServletRequestBuilder internalRequest(String path, String peerIp,
            Map<String, Object> body) throws Exception {
        return post(path)
                .with(request -> {
                    request.setRemoteAddr(peerIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private Set<String> detailKeys(String action, long vmId) {
        String detail = jdbcTemplate.queryForObject(
                "select detail::text from audit_logs where action = ? and target_id = ? "
                        + "order by id desc limit 1", String.class, action, vmId);
        JsonNode node = objectMapper.readTree(detail);
        return new TreeSet<>(node.propertyNames());
    }

    private void setWebTerminalEnabled(boolean enabled) {
        jdbcTemplate.update("update settings set value = ?::jsonb where key = 'web_terminal_enabled'",
                String.valueOf(enabled));
    }

    private long ensureNode() {
        Long existing = jdbcTemplate.query("select id from nodes where name = 'term-redeem-test'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        return jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage, ip_pool_id)
                values ('term-redeem-test', 'https://127.0.0.1:8006', 8, 16384, 'vmbr2', 'local-lvm', ?)
                returning id
                """, Long.class, poolId);
    }

    /** Simulates what a password change/reset does to every session of the account. */
    private void bumpTokenVersion() {
        jdbcTemplate.update("update users set token_version = token_version + 1 where id = ?",
                member.getId());
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private long createGroup() {
        return jdbcTemplate.queryForObject("""
                insert into groups (kind, name, slug)
                values ('TEAM'::group_kind, '리딤팀', ?) returning id
                """, Long.class, "termr-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void addMember(long groupId, long userId, String role) {
        jdbcTemplate.update("""
                insert into group_members (group_id, user_id, role)
                values (?, ?, ?::group_member_role)
                on conflict (group_id, user_id) do update set role = excluded.role
                """, groupId, userId, role);
    }

    private long createVm(VmStatus status, String ip, boolean blocked, String hostKey) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '리딤 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, groupId, orgId, member.getId(), imageId);
        long allocationId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, status) values (?, ?::inet, 'ALLOCATED')
                returning id
                """, Long.class, poolId, ip);
        String hostname = "termr-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status,
                                 ip_allocation_id, ssh_gateway_blocked, ssh_host_key)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?::vm_status, ?, ?, ?)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname, imageId,
                status.name(), allocationId, blocked, hostKey);
        jdbcTemplate.update("update ip_allocations set vm_id = ? where id = ?", vmId, allocationId);
        return vmId;
    }
}
