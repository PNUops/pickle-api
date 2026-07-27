package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Spec-preset catalog (contract v0.23.0), the request form's second axis: the
 * sys-tier list (all statuses, unlike the ACTIVE-only public list), the
 * SYS_ADMIN-only create/edit with change-only auditing, and the public list's
 * ACTIVE-only id ordering the wizard renders.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminVmFlavorTest {

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
    private String userToken;
    private long flavorId;
    private String flavorName;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        sysManagerToken = jwtService.createAccessToken(
                ensureUser("avf.sysmanager@pusan.ac.kr", UserRole.SYS_MANAGER));
        userToken = jwtService.createAccessToken(ensureUser("avf.user@pusan.ac.kr", UserRole.USER));
        flavorName = "avf-" + UUID.randomUUID().toString().substring(0, 8);
        flavorId = jdbcTemplate.queryForObject("""
                insert into vm_flavors (name, display_name, vcpu, memory_mb, disk_gb, status)
                values (?, '프리셋 편집 테스트', 2, 2048, 20, 'ACTIVE'::template_status)
                returning id
                """, Long.class, flavorName);
    }

    @Test
    void adminFlavorListShowsRetiredPresetsThePublicListHides() throws Exception {
        jdbcTemplate.update("update vm_flavors set status = 'DISABLED'::template_status where id = ?",
                flavorId);

        mockMvc.perform(get("/api/v1/admin/vm-flavors")
                        .header("Authorization", "Bearer " + sysManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(flavorId) + ".status").value("DISABLED"))
                .andExpect(jsonPath(byId(flavorId) + ".name").value(flavorName))
                .andExpect(jsonPath(byId(flavorId) + ".vcpu").value(2))
                .andExpect(jsonPath(byId(flavorId) + ".memoryMb").value(2048))
                .andExpect(jsonPath(byId(flavorId) + ".diskGb").value(20));

        mockMvc.perform(get("/api/v1/vm-flavors")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(flavorId)).doesNotExist());

        // the admin list is sys-tier only
        mockMvc.perform(get("/api/v1/admin/vm-flavors")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicFlavorListIsActiveOnlyAndOrderedById() throws Exception {
        // every authenticated role reads the wizard list; the V58 seed leads it
        mockMvc.perform(get("/api/v1/vm-flavors").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("small"))
                .andExpect(jsonPath("$[1].name").value("basic"))
                .andExpect(jsonPath("$[2].name").value("large"))
                .andExpect(jsonPath(byId(flavorId) + ".status").value("ACTIVE"));

        jdbcTemplate.update("update vm_flavors set status = 'DISABLED'::template_status where id = ?",
                flavorId);
        mockMvc.perform(get("/api/v1/vm-flavors").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(flavorId)).doesNotExist());
    }

    @Test
    void createIsSysAdminOnlyAndRejectsDuplicateNames() throws Exception {
        String name = "avf-new-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"name": "%s", "displayName": "초대형", "vcpu": 8, "memoryMb": 16384,
                 "diskGb": 80, "notes": "GPU 없는 대형 배치 작업용입니다."}
                """.formatted(name);

        mockMvc.perform(post("/api/v1/admin/vm-flavors")
                        .header("Authorization", "Bearer " + sysManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/vm-flavors")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.displayName").value("초대형"))
                .andExpect(jsonPath("$.vcpu").value(8))
                .andExpect(jsonPath("$.memoryMb").value(16384))
                .andExpect(jsonPath("$.diskGb").value(80))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.notes").isNotEmpty())
                .andExpect(jsonPath("$.id").isNumber());
        long createdId = jdbcTemplate.queryForObject(
                "select id from vm_flavors where name = ?", Long.class, name);
        assertThat(auditCount("flavor.create", createdId)).isEqualTo(1);

        // the name is the stable reference — a duplicate is a field error, not a 500
        mockMvc.perform(post("/api/v1/admin/vm-flavors")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("이미 존재하는 프리셋 이름입니다."));

        // bean validation still applies
        mockMvc.perform(post("/api/v1/admin/vm-flavors")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Bad Name", "displayName": "잘못된 이름", "vcpu": 1,
                                 "memoryMb": 1024, "diskGb": 10}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void patchIsSysAdminOnlyAndAuditsRealChangesOnly() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/vm-flavors/{id}", flavorId)
                        .header("Authorization", "Bearer " + sysManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vcpu\": 4}"))
                .andExpect(status().isForbidden());

        // partial edit of values and status in one call
        mockMvc.perform(patch("/api/v1/admin/vm-flavors/{id}", flavorId)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "기본형(개정)", "vcpu": 4, "memoryMb": 4096,
                                 "status": "DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("기본형(개정)"))
                .andExpect(jsonPath("$.vcpu").value(4))
                .andExpect(jsonPath("$.memoryMb").value(4096))
                // untouched fields survive a partial edit
                .andExpect(jsonPath("$.diskGb").value(20))
                .andExpect(jsonPath("$.name").value(flavorName))
                .andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(auditCount("flavor.update", flavorId)).isEqualTo(1);

        // re-applying the same values changes nothing → 200 without an audit row
        mockMvc.perform(patch("/api/v1/admin/vm-flavors/{id}", flavorId)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vcpu\": 4, \"status\": \"DISABLED\"}"))
                .andExpect(status().isOk());
        assertThat(auditCount("flavor.update", flavorId)).isEqualTo(1);

        // an empty body is a no-op request, not an edit → 422
        mockMvc.perform(patch("/api/v1/admin/vm-flavors/{id}", flavorId)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(patch("/api/v1/admin/vm-flavors/999999")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vcpu\": 2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
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
            User user = new User(email, "{test-no-login}", "프리셋테스트");
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
