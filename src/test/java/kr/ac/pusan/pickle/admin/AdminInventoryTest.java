package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
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

/**
 * Inventory operational-state write paths (contract v0.21.0): the sys-tier
 * admin template list (all statuses, unlike the ACTIVE-only public list), the
 * SYS_ADMIN-only status toggles with change-only auditing, and the
 * request-submit rejection of a retired template. Node MAINTENANCE placement
 * exclusion itself is covered by the placement service's ACTIVE filter.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminInventoryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String sysAdminToken;
    private String sysManagerToken;
    private long templateId;
    private long flavorId;
    private String templateName;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        sysManagerToken = jwtService.createAccessToken(
                ensureUser("ait.sysmanager@pusan.ac.kr", UserRole.SYS_MANAGER));
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        templateName = "ait-" + UUID.randomUUID().toString().substring(0, 8);
        templateId = jdbcTemplate.queryForObject("""
                insert into os_images (name, display_name, os_family, os_version, ssh_username,
                                          proxmox_vmid, node_id, version,
                                          min_disk_gb, status)
                values (?, '상태 토글 테스트', 'ubuntu', '24.04', 'ubuntu', 990001, ?, 1, 10,
                        'ACTIVE'::template_status)
                returning id
                """, Long.class, templateName, nodeId);
        flavorId = jdbcTemplate.queryForObject(
                "select id from vm_flavors where name = 'basic'", Long.class);
    }

    @Test
    void adminTemplateListShowsRetiredRevisionsThePublicListHides() throws Exception {
        jdbcTemplate.update("update os_images set status = 'DISABLED'::template_status where id = ?",
                templateId);

        mockMvc.perform(get("/api/v1/admin/templates")
                        .header("Authorization", "Bearer " + sysManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(templateId) + ".status").value("DISABLED"))
                .andExpect(jsonPath(byId(templateId) + ".proxmoxVmid").value(990001))
                .andExpect(jsonPath(byId(templateId) + ".minDiskGb").value(10))
                // distribution identity + the guest account the image ships
                .andExpect(jsonPath(byId(templateId) + ".osFamily").value("ubuntu"))
                .andExpect(jsonPath(byId(templateId) + ".osVersion").value("24.04"))
                .andExpect(jsonPath(byId(templateId) + ".sshUsername").value("ubuntu"))
                // spec presets are their own axis now (v0.23.0)
                .andExpect(jsonPath(byId(templateId) + ".defaultVcpu").doesNotExist());

        mockMvc.perform(get("/api/v1/templates")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(templateId)).doesNotExist());
    }

    @Test
    void templateToggleIsSysAdminOnlyAndAuditsRealTransitionsOnly() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/templates/{id}", templateId)
                        .header("Authorization", "Bearer " + sysManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DISABLED\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/admin/templates/{id}", templateId)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(jdbcTemplate.queryForObject(
                "select status from os_images where id = ?", String.class, templateId))
                .isEqualTo("DISABLED");
        assertThat(auditCount("template.status_update", templateId)).isEqualTo(1);

        // idempotent re-application: 200, no extra audit row
        mockMvc.perform(patch("/api/v1/admin/templates/{id}", templateId)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DISABLED\"}"))
                .andExpect(status().isOk());
        assertThat(auditCount("template.status_update", templateId)).isEqualTo(1);

        mockMvc.perform(patch("/api/v1/admin/templates/999999")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void retiredTemplateIsRejectedAtRequestSubmit() throws Exception {
        jdbcTemplate.update("update os_images set status = 'DISABLED'::template_status where id = ?",
                templateId);
        User requester = ensureUser("ait.user@pusan.ac.kr", UserRole.USER);
        String slug = "ait-" + UUID.randomUUID().toString().substring(0, 8);
        long groupId = jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
        jdbcTemplate.update("""
                insert into group_members (group_id, user_id, role)
                values (?, ?, 'OWNER'::group_member_role)
                """, groupId, requester.getId());
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/vm-requests")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupId": %d, "orgId": %d, "purpose": "은퇴 템플릿 거부 확인",
                                 "templateId": %d, "flavorId": %d, "reqVcpu": 2,
                                 "reqMemoryMb": 2048, "reqDiskGb": 20}
                                """.formatted(groupId, orgId, templateId, flavorId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("templateId"));
    }

    @Test
    void nodeStatusTransitionIsSysAdminOnlyAndAudited() throws Exception {
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        String original = jdbcTemplate.queryForObject(
                "select status from nodes where id = ?", String.class, nodeId);
        try {
            mockMvc.perform(patch("/api/v1/admin/nodes/{id}", nodeId)
                            .header("Authorization", "Bearer " + sysManagerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"MAINTENANCE\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/v1/admin/nodes/{id}", nodeId)
                            .header("Authorization", "Bearer " + sysAdminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"MAINTENANCE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MAINTENANCE"))
                    .andExpect(jsonPath("$.name").isNotEmpty());
            assertThat(jdbcTemplate.queryForObject(
                    "select status from nodes where id = ?", String.class, nodeId))
                    .isEqualTo("MAINTENANCE");
            assertThat(auditCount("node.status_update", nodeId)).isEqualTo(1);
        } finally {
            // the node pool is shared across test classes — put the row back
            jdbcTemplate.update("update nodes set status = ?::node_status where id = ?",
                    original, nodeId);
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static String byId(long id) {
        return "$[?(@.id == %d)]".formatted(id);
    }

    private long auditCount(String action, long targetId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = ? and target_id = ?",
                Long.class, action, targetId);
    }

    private User ensureUser(String email, UserRole role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "인벤토리테스트");
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
