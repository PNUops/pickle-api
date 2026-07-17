package kr.ac.pusan.pickle.vm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * VM guest-password surface per contract v0.7.0: the re-viewable reveal
 * (GET, ciphertext at rest, audited per reveal), the per-status 409 guard,
 * the MEMBER+/masking authorization, the no-store header, the 410 for rows
 * without a stored password, and the guest-agent reset (fresh credential
 * applied in-guest before the DB is updated; agent-down → 409).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InitialPasswordTest {

    /** Node name used in the WireMock stub paths (unique test inventory row). */
    private static final String NODE_NAME = "wm-passwd";

    private static final AtomicInteger VMID_SEQ = new AtomicInteger(930_000);
    private static final String PASSWORD = "x7GmQ4vRk2LpWn9sCtYb8Zed";

    private static ProxmoxWireMockSupport wm;

    @DynamicPropertySource
    static void proxmoxProperties(DynamicPropertyRegistry registry) {
        registry.add("pickle.proxmox.token-id", () -> "pickle@pve!pickle-api");
        registry.add("pickle.proxmox.token-secret", () -> "wiremock-test-secret");
    }

    @BeforeAll
    static void startServer() {
        wm = ProxmoxWireMockSupport.start();
    }

    @AfterAll
    static void stopServer() {
        wm.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CredentialCipher credentialCipher;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User owner;
    private User viewer;
    private User outsider;
    private String ownerToken;
    private String viewerToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long templateId;
    private long groupId;

    @BeforeEach
    void setUp() throws Exception {
        wm.reset();
        owner = ensureUser("vmpw.owner@pusan.ac.kr", "비번소유자");
        viewer = ensureUser("vmpw.viewer@pusan.ac.kr", "비번뷰어");
        outsider = ensureUser("vmpw.outsider@pusan.ac.kr", "비번외부인");
        ownerToken = jwtService.createAccessToken(owner);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = jdbcTemplate.queryForObject("select id from orgs where slug = 'sw-edu'", Long.class);
        nodeId = ensureWireMockNode();
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        groupId = createTeam("vmpw-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(groupId, viewer.getEmail(), "VIEWER");
    }

    @Test
    void revealsRepeatedlyFromCiphertext() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, PASSWORD);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/vms/" + vmId + "/initial-password")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.password").value(PASSWORD))
                    .andExpect(jsonPath("$.sshUsername").value("student"));
        }

        // ciphertext survives reveals; the reveal time is recorded
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select initial_password_enc, initial_password_hash,
                       initial_password_viewed_at is not null as viewed
                  from vms where id = ?
                """, vmId);
        assertThat((String) row.get("initial_password_enc")).startsWith("v1:");
        assertThat((String) row.get("initial_password_enc")).doesNotContain(PASSWORD);
        assertThat(row.get("viewed")).isEqualTo(true);

        // every reveal is audited as a fact — never the value
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                select action, coalesce(detail::text, '') as detail from audit_logs
                 where action = 'vm.password_reveal' and target_id = ?
                """, vmId);
        assertThat(audits).hasSize(2);
        assertThat((String) audits.getFirst().get("detail")).doesNotContain(PASSWORD);
        // nothing lands in vm_events either
        Long events = jdbcTemplate.queryForObject(
                "select count(*) from vm_events where vm_id = ?", Long.class, vmId);
        assertThat(events).isZero();
    }

    @Test
    void stateAndRoleGuards() throws Exception {
        long vmId = createVm(VmStatus.CREATING, PASSWORD);

        // CREATING/DELETING/DELETED/ERROR/NEEDS_ADMIN → 409, ciphertext kept
        for (VmStatus status : List.of(VmStatus.CREATING, VmStatus.DELETING, VmStatus.DELETED,
                VmStatus.ERROR, VmStatus.NEEDS_ADMIN)) {
            jdbcTemplate.update("update vms set status = ?::vm_status where id = ?",
                    status.name(), vmId);
            mockMvc.perform(get("/api/v1/vms/" + vmId + "/initial-password")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
        }
        assertThat(jdbcTemplate.queryForObject(
                "select initial_password_enc from vms where id = ?", String.class, vmId))
                .isNotNull();

        // VIEWER → 403, non-member → 404 (masked), unauthenticated → 401
        jdbcTemplate.update("update vms set status = 'RUNNING' where id = ?", vmId);
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/initial-password")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/initial-password")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/initial-password"))
                .andExpect(status().isUnauthorized());

        // a VM without a stored password (e.g. M2 mock-provisioned) → 410
        long mockVm = createVm(VmStatus.RUNNING, null);
        mockMvc.perform(get("/api/v1/vms/" + mockVm + "/initial-password")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VM_PASSWORD_ALREADY_VIEWED"));
    }

    @Test
    void resetAppliesInGuestBeforeStoringAndAnswersConflictWhenAgentIsDown() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, PASSWORD);
        int proxmoxVmid = jdbcTemplate.queryForObject(
                "select proxmox_vmid from vms where id = ?", Integer.class, vmId);
        String agentPath = "/api2/json/nodes/" + NODE_NAME + "/qemu/" + proxmoxVmid
                + "/agent/set-user-password";
        wm.server().stubFor(WireMock.post(urlPathEqualTo(agentPath))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":null}")));

        String body = mockMvc.perform(post("/api/v1/vms/" + vmId + "/password-reset")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sshUsername").value("student"))
                .andReturn().getResponse().getContentAsString();
        String newPassword = objectMapper.readTree(body).get("password").asString();
        assertThat(newPassword).hasSize(24).isNotEqualTo(PASSWORD);
        wm.server().verify(1, postRequestedFor(urlPathEqualTo(agentPath)));

        // the stored ciphertext/hash now match the new password, and the reveal
        // endpoint returns it
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select initial_password_enc, initial_password_hash from vms where id = ?", vmId);
        assertThat(credentialCipher.decrypt((String) row.get("initial_password_enc")))
                .isEqualTo(newPassword);
        assertThat(passwordEncoder.matches(newPassword, (String) row.get("initial_password_hash")))
                .isTrue();
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/initial-password")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").value(newPassword));
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                select coalesce(detail::text, '') as detail from audit_logs
                 where action = 'vm.password_reset' and target_id = ?
                """, vmId);
        assertThat(audits).hasSize(1);
        assertThat((String) audits.getFirst().get("detail")).doesNotContain(newPassword);

        // agent down → 409, stored credentials untouched
        wm.server().stubFor(WireMock.post(urlPathEqualTo(agentPath))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":null,\"message\":\"QEMU guest agent is not running\"}")));
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/password-reset")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
        assertThat(credentialCipher.decrypt(jdbcTemplate.queryForObject(
                "select initial_password_enc from vms where id = ?", String.class, vmId)))
                .isEqualTo(newPassword);

        // not RUNNING → 409 without touching Proxmox; VIEWER 403; outsider 404
        jdbcTemplate.update("update vms set status = 'STOPPED' where id = ?", vmId);
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/password-reset")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
        jdbcTemplate.update("update vms set status = 'RUNNING' where id = ?", vmId);
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/password-reset")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/password-reset")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** One shared test node whose api_host points at this class's WireMock. */
    private long ensureWireMockNode() {
        Long existing = jdbcTemplate.query(
                "select id from nodes where name = ?",
                rs -> rs.next() ? rs.getLong(1) : null, NODE_NAME);
        if (existing != null) {
            jdbcTemplate.update("update nodes set api_host = ? where id = ?", wm.apiHost(), existing);
            return existing;
        }
        return jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, ?, 40, 79872, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, NODE_NAME, wm.apiHost());
    }

    private long createVm(VmStatus status, String initialPassword) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '비밀번호 테스트', ?, 1, 1024, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, owner.getId(), templateId);
        String hostname = "vmpw-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status,
                                 initial_password_enc, initial_password_hash)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, ?::vm_status, ?, ?)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, VMID_SEQ.incrementAndGet(), status.name(),
                initialPassword == null ? null : credentialCipher.encrypt(initialPassword),
                initialPassword == null ? null : "bcrypt-hash");
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "비번 테스트 " + slug, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void addMember(long groupId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "role", role))))
                .andExpect(status().isCreated());
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
