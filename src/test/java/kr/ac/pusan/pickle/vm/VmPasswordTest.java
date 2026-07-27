package kr.ac.pusan.pickle.vm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
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
import tools.jackson.databind.ObjectMapper;

/**
 * VM guest-password reveal per contract v0.7.1: re-viewable (GET, ciphertext
 * at rest, audited per reveal), the per-status 409 guard, the MEMBER+/masking
 * authorization, the no-store header, and the 410 for rows without a stored
 * password.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmPasswordTest {

    private static final AtomicInteger VMID_SEQ = new AtomicInteger(930_000);
    private static final String PASSWORD = "x7GmQ4vRk2LpWn9sCtYb8Zed";

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
    private User member;
    private User viewer;
    private User outsider;
    private String ownerToken;
    private String memberToken;
    private String viewerToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long templateId;
    private long groupId;

    @BeforeEach
    void setUp() throws Exception {
        owner = ensureUser("vmpw.owner@pusan.ac.kr", "비번소유자");
        member = ensureUser("vmpw.member@pusan.ac.kr", "비번멤버");
        viewer = ensureUser("vmpw.viewer@pusan.ac.kr", "비번뷰어");
        outsider = ensureUser("vmpw.outsider@pusan.ac.kr", "비번외부인");
        ownerToken = jwtService.createAccessToken(owner);
        memberToken = jwtService.createAccessToken(member);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        groupId = createTeam("vmpw-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(groupId, member.getEmail(), "MEMBER");
        addMember(groupId, viewer.getEmail(), "VIEWER");
    }

    @Test
    void revealMinRoleRaiseBlocksMember() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, PASSWORD);
        // default min-role MEMBER: a MEMBER may reveal
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        // raise password_reveal_min_role to EDITOR → the MEMBER is now blocked
        jdbcTemplate.update("""
                insert into vm_settings (vm_id, key, value, updated_at)
                values (?, 'password_reveal_min_role', '"EDITOR"'::jsonb, now())
                """, vmId);
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        // the OWNER still can (OWNER ≥ EDITOR)
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void regenerateAuthzAndStateGuardsBeforeAgent() throws Exception {
        // MEMBER/VIEWER are below EDITOR → 403; non-member → 404
        long running = createVm(VmStatus.RUNNING, PASSWORD);
        mockMvc.perform(post("/api/v1/vms/" + running + "/password/regenerate")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        mockMvc.perform(post("/api/v1/vms/" + running + "/password/regenerate")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        // OWNER on a non-RUNNING VM → 409 before any guest-agent call
        long stopped = createVm(VmStatus.STOPPED, PASSWORD);
        mockMvc.perform(post("/api/v1/vms/" + stopped + "/password/regenerate")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
    }

    @Test
    void revealsRepeatedlyFromCiphertext() throws Exception {
        long vmId = createVm(VmStatus.RUNNING, PASSWORD);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.password").value(PASSWORD))
                    .andExpect(jsonPath("$.sshUsername").value("ubuntu"));
        }

        // ciphertext survives reveals; the reveal time is recorded
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select password_enc, password_hash,
                       password_viewed_at is not null as viewed
                  from vms where id = ?
                """, vmId);
        assertThat((String) row.get("password_enc")).startsWith("v1:");
        assertThat((String) row.get("password_enc")).doesNotContain(PASSWORD);
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
            mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
        }
        assertThat(jdbcTemplate.queryForObject(
                "select password_enc from vms where id = ?", String.class, vmId))
                .isNotNull();

        // VIEWER → 403, non-member → 404 (masked), unauthenticated → 401
        jdbcTemplate.update("update vms set status = 'RUNNING' where id = ?", vmId);
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/password"))
                .andExpect(status().isUnauthorized());

        // a VM without a stored password (e.g. a mock-provisioned VM) → 410
        long mockVm = createVm(VmStatus.RUNNING, null);
        mockMvc.perform(get("/api/v1/vms/" + mockVm + "/password")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VM_PASSWORD_ALREADY_VIEWED"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private long createVm(VmStatus status, String initialPassword) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '비밀번호 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, groupId, orgId, owner.getId(), templateId);
        String hostname = "vmpw-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status,
                                 password_enc, password_hash)
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
