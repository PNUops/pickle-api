package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
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
 * admin OS image list (all statuses, unlike the ACTIVE-only public list), the
 * SYS_ADMIN-only status toggles with change-only auditing, and the
 * request-submit rejection of a retired OS image. Node MAINTENANCE placement
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
    private long imageId;
    private long flavorId;
    private String imageName;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        sysManagerToken = jwtService.createAccessToken(
                ensureUser("ait.sysmanager@pusan.ac.kr", UserRole.SYS_MANAGER));
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        imageName = "ait-" + UUID.randomUUID().toString().substring(0, 8);
        imageId = jdbcTemplate.queryForObject("""
                insert into os_images (name, display_name, os_family, os_version, ssh_username,
                                          proxmox_vmid, node_id, version,
                                          min_disk_gb, status)
                values (?, '상태 토글 테스트', 'ubuntu', '24.04', 'ubuntu', 990001, ?, 1, 10,
                        'ACTIVE'::catalog_status)
                returning id
                """, Long.class, imageName, nodeId);
        flavorId = jdbcTemplate.queryForObject(
                "select id from vm_flavors where name = 'highmem'", Long.class);
    }

    @Test
    void adminOsImageListShowsRetiredRevisionsThePublicListHides() throws Exception {
        jdbcTemplate.update("update os_images set status = 'DISABLED'::catalog_status where id = ?",
                imageId);

        mockMvc.perform(get("/api/v1/admin/os-images")
                        .header("Authorization", "Bearer " + sysManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(imageId) + ".status").value("DISABLED"))
                .andExpect(jsonPath(byId(imageId) + ".proxmoxVmid").value(990001))
                .andExpect(jsonPath(byId(imageId) + ".minDiskGb").value(10))
                // distribution identity + the guest account the image ships
                .andExpect(jsonPath(byId(imageId) + ".osFamily").value("ubuntu"))
                .andExpect(jsonPath(byId(imageId) + ".osVersion").value("24.04"))
                .andExpect(jsonPath(byId(imageId) + ".sshUsername").value("ubuntu"))
                // spec presets are their own axis now (v0.23.0)
                .andExpect(jsonPath(byId(imageId) + ".defaultVcpu").doesNotExist());

        mockMvc.perform(get("/api/v1/os-images")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(imageId)).doesNotExist());
    }

    /**
     * The admin catalog is the same catalog the wizard shows, so it is read in
     * the same order — the admin decides what students see and should not have
     * to translate between two orderings. The retired revision sorts by its own
     * family and release like any other row; status is a column, not a section.
     */
    @Test
    void adminOsImageListFollowsTheWizardDisplayOrder() throws Exception {
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        // registered newest-release-first, so id order agrees with the display
        // order here; the release strings sort the other way as text ('10' < '9'),
        // so only numeric release order keeps Rocky 10 ahead of Rocky 9
        String rocky10 = insertImage(nodeId, "rocky", "10", "DISABLED");
        String rocky9 = insertImage(nodeId, "rocky", "9", "ACTIVE");
        String debian13 = insertImage(nodeId, "debian", "13", "ACTIVE");

        String body = mockMvc.perform(get("/api/v1/admin/os-images")
                        .header("Authorization", "Bearer " + sysManagerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(body, "$[*].name");

        // imageName is the setUp row, an ubuntu 24.04 — last of the four families
        assertThat(names).containsSubsequence(debian13, rocky10, rocky9, imageName);
    }

    @Test
    void osImageToggleIsSysAdminOnlyAndAuditsRealTransitionsOnly() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/os-images/{id}", pub("os_images", imageId))
                        .header("Authorization", "Bearer " + sysManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DISABLED\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/admin/os-images/{id}", pub("os_images", imageId))
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(jdbcTemplate.queryForObject(
                "select status from os_images where id = ?", String.class, imageId))
                .isEqualTo("DISABLED");
        assertThat(auditCount("os_image.status_update", "os_images", imageId)).isEqualTo(1);

        // idempotent re-application: 200, no extra audit row
        mockMvc.perform(patch("/api/v1/admin/os-images/{id}", pub("os_images", imageId))
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DISABLED\"}"))
                .andExpect(status().isOk());
        assertThat(auditCount("os_image.status_update", "os_images", imageId)).isEqualTo(1);

        mockMvc.perform(patch("/api/v1/admin/os-images/" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void retiredOsImageIsRejectedAtRequestSubmit() throws Exception {
        jdbcTemplate.update("update os_images set status = 'DISABLED'::catalog_status where id = ?",
                imageId);
        User requester = ensureUser("ait.user@pusan.ac.kr", UserRole.USER);
        String slug = "ait-" + UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'OWNER'::workspace_member_role)
                """, workspaceId, requester.getId());
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/requests")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "VM", "workspaceId": "%s", "orgId": "%s",
                                 "purpose": "은퇴 OS 이미지 거부 확인",
                                 "displayName": "은퇴 이미지 확인",
                                 "reqEndDate": "%s",
                                 "vm": {"imageId": "%s", "flavorId": "%s", "reqVcpu": 1,
                                        "reqMemoryMb": 2048, "reqDiskGb": 32}}
                                """.formatted(pub("workspaces", workspaceId), pub("orgs", orgId),
                                LocalDate.now(ClockConfig.KST).plusMonths(4),
                                pub("os_images", imageId), pub("vm_flavors", flavorId))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("vm.imageId"));
    }

    @Test
    void nodeStatusTransitionIsSysAdminOnlyAndAudited() throws Exception {
        long nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        String original = jdbcTemplate.queryForObject(
                "select status from nodes where id = ?", String.class, nodeId);
        try {
            mockMvc.perform(patch("/api/v1/admin/nodes/{id}", pub("nodes", nodeId))
                            .header("Authorization", "Bearer " + sysManagerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"MAINTENANCE\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/v1/admin/nodes/{id}", pub("nodes", nodeId))
                            .header("Authorization", "Bearer " + sysAdminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"MAINTENANCE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("MAINTENANCE"))
                    .andExpect(jsonPath("$.name").isNotEmpty());
            assertThat(jdbcTemplate.queryForObject(
                    "select status from nodes where id = ?", String.class, nodeId))
                    .isEqualTo("MAINTENANCE");
            assertThat(auditCount("node.status_update", "nodes", nodeId)).isEqualTo(1);
        } finally {
            // the node pool is shared across test classes — put the row back
            jdbcTemplate.update("update nodes set status = ?::node_status where id = ?",
                    original, nodeId);
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private String byId(long id) {
        return "$[?(@.id == '%s')]".formatted(pub("os_images", id));
    }

    private String insertImage(long nodeId, String family, String version, String status) {
        String name = family + "-" + version + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                insert into os_images (name, display_name, os_family, os_version, ssh_username,
                                          proxmox_vmid, node_id, version, min_disk_gb, status)
                values (?, '정렬 확인용', ?, ?, ?, 990002, ?, 1, 10, cast(? as catalog_status))
                """, name, family, version, family, nodeId, status);
        return name;
    }

    private long auditCount(String action, String table, long targetId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = ? and target_id = ?",
                Long.class, action, pub(table, targetId).toString());
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

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
