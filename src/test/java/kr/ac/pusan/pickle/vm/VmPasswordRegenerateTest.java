package kr.ac.pusan.pickle.vm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Password regeneration against the guest agent (contract op
 * {@code regenerateVmPassword}): the live-apply happy path (agent 200 → new
 * password stored, audited, no-store) and the agent-unavailable 409.
 */
@SpringBootTest(properties = {
        "pickle.proxmox.token-id=pickle@pve!pickle-api",
        "pickle.proxmox.token-secret=wiremock-test-secret",
        "pickle.proxmox.connect-timeout=1s",
        "pickle.proxmox.read-timeout=2s"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmPasswordRegenerateTest {

    private static final String NODE = "pve1";
    private static final String OLD_PASSWORD = "x7GmQ4vRk2LpWn9sCtYb8Zed";
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(970_000);

    private static ProxmoxWireMockSupport wm;

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

    private User owner;
    private String ownerToken;
    private String ownerReauth;
    private long orgId;
    private long nodeId;
    private long templateId;
    private long groupId;

    @BeforeAll
    static void startServer() {
        wm = ProxmoxWireMockSupport.start();
    }

    @AfterAll
    static void stopServer() {
        wm.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        wm.reset();
        jdbcTemplate.update("update nodes set api_host = ? where name = ?", wm.apiHost(), NODE);
        owner = ensureUser("vmpwregen.owner@pusan.ac.kr", "재생성소유자");
        ownerToken = jwtService.createAccessToken(owner);
        ownerReauth = ReauthTestSupport.seededReauthHeader(jdbcTemplate, owner.getId());
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        nodeId = jdbcTemplate.queryForObject("select id from nodes where name = ?", Long.class, NODE);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        groupId = createTeam("vmpwregen-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void regeneratesLiveViaGuestAgent() throws Exception {
        int vmid = VMID_SEQ.incrementAndGet();
        long vmId = createRunningVm(vmid);
        wm.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(
                "/api2/json/nodes/" + NODE + "/qemu/" + vmid + "/agent/set-user-password"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{\"data\":null}")));

        String body = mockMvc.perform(post("/api/v1/vms/" + vmId + "/password/regenerate")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, ownerReauth))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sshUsername").value("ubuntu"))
                .andExpect(jsonPath("$.sshHost").value("ssh.pickle.pnuops.com"))
                .andReturn().getResponse().getContentAsString();
        String newPassword = objectMapper.readTree(body).get("password").asString();
        assertThat(newPassword).hasSize(24).isNotEqualTo(OLD_PASSWORD);

        // the guest agent was driven for the guest account
        wm.server().verify(1, postRequestedFor(urlPathEqualTo(
                "/api2/json/nodes/" + NODE + "/qemu/" + vmid + "/agent/set-user-password"))
                .withRequestBody(containing("username=ubuntu")));

        // stored ciphertext now decrypts to the new password (not the old one)
        String enc = jdbcTemplate.queryForObject(
                "select password_enc from vms where id = ?", String.class, vmId);
        assertThat(credentialCipher.decrypt(enc)).isEqualTo(newPassword);

        // audited as a fact, never the value
        String detail = jdbcTemplate.queryForObject(
                "select coalesce(detail::text, '') from audit_logs "
                        + "where action = 'vm.password_regenerate' and target_id = ?",
                String.class, vmId);
        assertThat(detail).doesNotContain(newPassword);
    }

    @Test
    void agentUnavailableIsConflict() throws Exception {
        int vmid = VMID_SEQ.incrementAndGet();
        long vmId = createRunningVm(vmid);
        // PVE returns 500 when the guest agent is not running
        wm.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(
                "/api2/json/nodes/" + NODE + "/qemu/" + vmid + "/agent/set-user-password"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":null,\"message\":\"QEMU guest agent is not running\"}")));

        mockMvc.perform(post("/api/v1/vms/" + vmId + "/password/regenerate")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, ownerReauth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));

        // the old ciphertext is untouched on failure
        String enc = jdbcTemplate.queryForObject(
                "select password_enc from vms where id = ?", String.class, vmId);
        assertThat(credentialCipher.decrypt(enc)).isEqualTo(OLD_PASSWORD);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private long createRunningVm(int vmid) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '재생성 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, groupId, orgId, owner.getId(), templateId);
        String hostname = "vmpwregen-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status,
                                 password_enc, password_hash)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, 'RUNNING'::vm_status, ?, ?)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, vmid, credentialCipher.encrypt(OLD_PASSWORD), "bcrypt-hash");
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("kind", "TEAM", "name", "재생성 " + slug, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
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
