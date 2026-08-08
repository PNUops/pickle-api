package kr.ac.pusan.pickle.vmsettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
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
 * VM settings surface (contract v0.8.0): the EDITOR+ read gate with non-member
 * masking, per-key required-role on PATCH (ssh_password_enabled→EDITOR,
 * password_reveal_min_role→OWNER), 422/409 guards, the audited old→new trail,
 * and the enforcement getters feature code relies on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmSettingsTest {

    private static final AtomicInteger VMID_SEQ = new AtomicInteger(960_000);

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
    private VmSettingsService vmSettingsService;

    private User owner;
    private User editor;
    private User viewer;
    private User outsider;
    private String ownerToken;
    private String editorToken;
    private String viewerToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long imageId;
    private long groupId;

    @BeforeEach
    void setUp() throws Exception {
        owner = ensureUser("vmset.owner@pusan.ac.kr", "설정소유자");
        editor = ensureUser("vmset.editor@pusan.ac.kr", "설정편집자");
        viewer = ensureUser("vmset.viewer@pusan.ac.kr", "설정뷰어");
        outsider = ensureUser("vmset.outsider@pusan.ac.kr", "외부인");
        ownerToken = jwtService.createAccessToken(owner);
        editorToken = jwtService.createAccessToken(editor);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        groupId = createTeam("vmset-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(groupId, editor.getEmail(), "EDITOR");
        addMember(groupId, viewer.getEmail(), "VIEWER");
    }

    @Test
    void readGateAndDefaults() throws Exception {
        long vmId = createVm();
        // EDITOR sees the two-key catalog with defaults
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/settings")
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("ssh_password_enabled"))
                .andExpect(jsonPath("$[0].value").value(false))
                .andExpect(jsonPath("$[0].editable").value(true))
                .andExpect(jsonPath("$[1].key").value("password_reveal_min_role"))
                .andExpect(jsonPath("$[1].value").value("MEMBER"))
                // EDITOR cannot change the OWNER-only key
                .andExpect(jsonPath("$[1].editable").value(false));

        // VIEWER 403, non-member 404
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/settings")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/settings")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void editorTogglesPasswordSshAndItIsAuditedAndEnforced() throws Exception {
        long vmId = createVm();
        patchSettings(editorToken, vmId, Map.of("ssh_password_enabled", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("ssh_password_enabled"))
                .andExpect(jsonPath("$[0].value").value(true))
                .andExpect(jsonPath("$[0].updatedByName").value("설정편집자"));

        // enforcement getter reflects the override
        assertThat(vmSettingsService.bool(vmId, VmSettingsService.SSH_PASSWORD_ENABLED)).isTrue();

        // audited old→new, no secrets
        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "select detail::text as detail from audit_logs where action = 'vm.setting_update' "
                        + "and target_id = ?", vmId);
        assertThat(audits).hasSize(1);
        assertThat((String) audits.getFirst().get("detail"))
                .contains("ssh_password_enabled").contains("false").contains("true");
    }

    @Test
    void perKeyRoleGate() throws Exception {
        long vmId = createVm();
        // EDITOR cannot change the OWNER-only key
        patchSettings(editorToken, vmId, Map.of("password_reveal_min_role", "EDITOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("password_reveal_min_role")));

        // OWNER can, and the enforcement getter follows
        patchSettings(ownerToken, vmId, Map.of("password_reveal_min_role", "EDITOR"))
                .andExpect(status().isOk());
        assertThat(vmSettingsService.role(vmId, VmSettingsService.PASSWORD_REVEAL_MIN_ROLE))
                .isEqualTo(GroupMemberRole.EDITOR);
    }

    @Test
    void validationAndStateGuards() throws Exception {
        long vmId = createVm();
        // unknown key
        patchSettings(ownerToken, vmId, Map.of("nope", true))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // wrong type
        patchSettings(ownerToken, vmId, Map.of("ssh_password_enabled", "yes"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("settings.ssh_password_enabled"));
        // bad enum value
        patchSettings(ownerToken, vmId, Map.of("password_reveal_min_role", "GOD"))
                .andExpect(status().isUnprocessableEntity());
        // empty map
        patchSettings(ownerToken, vmId, Map.of())
                .andExpect(status().isUnprocessableEntity());
        // DELETING VM → 409
        jdbcTemplate.update("update vms set status = 'DELETING' where id = ?", vmId);
        patchSettings(ownerToken, vmId, Map.of("ssh_password_enabled", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
    }

    @Test
    void m6KeysAppearInCatalogWithRolesAndDefaults() throws Exception {
        long vmId = createVm();
        mockMvc.perform(get("/api/v1/vms/" + vmId + "/settings")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='deletion_protection')].value")
                        .value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.key=='deletion_protection')].requiredRole")
                        .value(org.hamcrest.Matchers.contains("OWNER")))
                .andExpect(jsonPath("$[?(@.key=='stop_protection')].value")
                        .value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.key=='display_name')].valueType")
                        .value(org.hamcrest.Matchers.contains("STRING")))
                .andExpect(jsonPath("$[?(@.key=='display_name')].value")
                        .value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void displayNameEditableByEditorWithLengthCapAndClear() throws Exception {
        long vmId = createVm();
        patchSettings(editorToken, vmId, Map.of("display_name", "실습 서버 A"))
                .andExpect(status().isOk());
        assertThat(vmSettingsService.string(vmId, VmSettingsService.DISPLAY_NAME))
                .isEqualTo("실습 서버 A");
        // VmDetail carries the displayName + orgName join (display fields)
        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("실습 서버 A"))
                .andExpect(jsonPath("$.orgName").isNotEmpty());
        // empty string clears (getter returns null)
        patchSettings(editorToken, vmId, Map.of("display_name", ""))
                .andExpect(status().isOk());
        assertThat(vmSettingsService.string(vmId, VmSettingsService.DISPLAY_NAME)).isNull();
        // control/format chars (zero-width filler, RTL override) are stripped
        // here as well: the approval seed already owned this invariant, and a
        // later PATCH must not be able to put a spoofable name back
        String zeroWidth = new String(new int[] {0x200B, 0x200D, 0xFEFF}, 0, 3);
        String rtlOverride = new String(new int[] {0x202E}, 0, 1);
        patchSettings(editorToken, vmId,
                Map.of("display_name", zeroWidth + "실습" + rtlOverride + " 서버 B" + zeroWidth))
                .andExpect(status().isOk());
        assertThat(vmSettingsService.string(vmId, VmSettingsService.DISPLAY_NAME))
                .isEqualTo("실습 서버 B");
        // a name made of nothing but those chars is a clear, not a stored name
        patchSettings(editorToken, vmId, Map.of("display_name", zeroWidth))
                .andExpect(status().isOk());
        assertThat(vmSettingsService.string(vmId, VmSettingsService.DISPLAY_NAME)).isNull();
        // ...and the cap now measures the visible text: 100 real chars plus
        // zero-width filler is still within it
        patchSettings(editorToken, vmId, Map.of("display_name", zeroWidth + "가".repeat(100)))
                .andExpect(status().isOk());
        assertThat(vmSettingsService.string(vmId, VmSettingsService.DISPLAY_NAME))
                .isEqualTo("가".repeat(100));
        // over 100 chars → 422
        patchSettings(editorToken, vmId, Map.of("display_name", "가".repeat(101)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("settings.display_name"));
    }

    @Test
    void protectionKeysAreOwnerOnly() throws Exception {
        long vmId = createVm();
        // EDITOR blocked from both protection keys (role gate)
        patchSettings(editorToken, vmId, Map.of("deletion_protection", true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        patchSettings(editorToken, vmId, Map.of("stop_protection", true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        // Both keys are pure pickle-side state (the PVE protection flag is
        // always-on platform state, not mirrored from here), so OWNER toggles
        // succeed even on this vmid-less VM with no Proxmox behind it.
        patchSettings(ownerToken, vmId, Map.of("stop_protection", true))
                .andExpect(status().isOk());
        assertThat(vmSettingsService.bool(vmId, VmSettingsService.STOP_PROTECTION)).isTrue();
        patchSettings(ownerToken, vmId, Map.of("deletion_protection", true))
                .andExpect(status().isOk());
        assertThat(vmSettingsService.bool(vmId, VmSettingsService.DELETION_PROTECTION)).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Settings patch and member management are sudo-mode gated. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    private org.springframework.test.web.servlet.ResultActions patchSettings(String token,
            long vmId, Map<String, Object> settings) throws Exception {
        return mockMvc.perform(patch("/api/v1/vms/" + vmId + "/settings")
                .header("Authorization", "Bearer " + token)
                .header(ReauthTestSupport.HEADER, reauth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("settings", settings))));
    }

    private long createVm() {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '설정 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, groupId, orgId, owner.getId(), imageId);
        String hostname = "vmset-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                imageId, VMID_SEQ.incrementAndGet());
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "설정 테스트 " + slug, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void addMember(long groupId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken))
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
