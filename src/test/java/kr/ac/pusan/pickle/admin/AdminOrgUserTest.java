package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Admin org/user management per contract: SYS_ADMIN-only gates, org slug
 * uniqueness, ORG_ADMIN⇒orgId validation and the token_version bump on role
 * change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminOrgUserTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User sysAdmin;
    private User orgAdmin;
    private User student;
    private String sysAdminToken;
    private String orgAdminToken;
    private String studentToken;

    @BeforeEach
    void setUp() {
        Org org = orgRepository.findBySlug("adm-org-base").orElseGet(
                () -> orgRepository.save(new Org("관리 테스트 기관", "adm-org-base", null)));
        sysAdmin = ensureUser("adm.sysadmin@pusan.ac.kr", "시스템관리자", UserRole.SYS_ADMIN, null);
        orgAdmin = ensureUser("adm.orgadmin@pusan.ac.kr", "기관관리자", UserRole.ORG_ADMIN, org.getId());
        student = ensureUser("adm.student@pusan.ac.kr", "학생", UserRole.STUDENT, null);
        sysAdminToken = jwtService.createAccessToken(sysAdmin);
        orgAdminToken = jwtService.createAccessToken(orgAdmin);
        studentToken = jwtService.createAccessToken(student);
    }

    @Test
    void adminEndpointsAreSysAdminOnly() throws Exception {
        Map<String, ?> body = Map.of("name", "새 기관", "slug", "adm-gate-x1");
        // ORG_ADMIN and STUDENT are rejected with 403 ACCESS_DENIED
        postJson("/api/v1/admin/orgs", orgAdminToken, body)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        postJson("/api/v1/admin/orgs", studentToken, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchJson("/api/v1/admin/users/" + student.getId(), orgAdminToken, Map.of("role", "SYS_ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/admin/orgs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void orgCreateAndUpdateLifecycle() throws Exception {
        postJson("/api/v1/admin/orgs", sysAdminToken,
                Map.of("name", "정보컴퓨터공학부 실습지원센터", "slug", "adm-org-x1", "description", "실습 자원 제공"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("정보컴퓨터공학부 실습지원센터"))
                .andExpect(jsonPath("$.slug").value("adm-org-x1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        long orgId = orgRepository.findBySlug("adm-org-x1").orElseThrow().getId();

        // duplicate slug → 409 ORG_SLUG_DUPLICATE
        postJson("/api/v1/admin/orgs", sysAdminToken, Map.of("name", "다른 기관", "slug", "adm-org-x1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORG_SLUG_DUPLICATE"));

        // invalid slug → 422
        postJson("/api/v1/admin/orgs", sysAdminToken, Map.of("name", "기관", "slug", "Bad_Slug!"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("slug"));

        // patch name + status
        patchJson("/api/v1/admin/orgs/" + orgId, sysAdminToken,
                Map.of("name", "실습지원센터(개편)", "status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("실습지원센터(개편)"))
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // DISABLED orgs disappear from the student-facing reference list
        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'adm-org-x1')]").isEmpty());

        // empty patch → 422, unknown org → 404
        patchJson("/api/v1/admin/orgs/" + orgId, sysAdminToken, Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        patchJson("/api/v1/admin/orgs/999999", sysAdminToken, Map.of("name", "유령 기관"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // org.create / org.update audit rows
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action in ('org.create', 'org.update') and target_id = ?",
                Long.class, orgId);
        assertThat(audits).isGreaterThanOrEqualTo(2);
    }

    @Test
    void userRoleUpdateValidatesOrgIdAndBumpsTokenVersion() throws Exception {
        User target = ensureUser("adm.promotee@pusan.ac.kr", "승격대상", UserRole.STUDENT, null);
        String targetToken = jwtService.createAccessToken(target);
        int versionBefore = target.getTokenVersion();
        Org org = orgRepository.findBySlug("adm-org-base").orElseThrow();

        // ORG_ADMIN requires orgId → 422 with the field error
        patchJson("/api/v1/admin/users/" + target.getId(), sysAdminToken, Map.of("role", "ORG_ADMIN"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("orgId"));

        // unknown orgId → 422
        patchJson("/api/v1/admin/users/" + target.getId(), sysAdminToken,
                Map.of("role", "ORG_ADMIN", "orgId", 999999))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("orgId"));

        // STUDENT/SYS_ADMIN must not carry an orgId → 422
        patchJson("/api/v1/admin/users/" + target.getId(), sysAdminToken,
                Map.of("role", "STUDENT", "orgId", org.getId()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("orgId"));

        // empty patch → 422, unknown user → 404
        patchJson("/api/v1/admin/users/" + target.getId(), sysAdminToken, Map.of())
                .andExpect(status().isUnprocessableContent());
        patchJson("/api/v1/admin/users/999999", sysAdminToken, Map.of("role", "STUDENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // promotion works and returns the contract UserSummary
        patchJson("/api/v1/admin/users/" + target.getId(), sysAdminToken,
                Map.of("role", "ORG_ADMIN", "orgId", org.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId()))
                .andExpect(jsonPath("$.email").value(target.getEmail()))
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"));

        // role change bumped token_version → the old access token is dead
        User reloaded = userRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isEqualTo(versionBefore + 1);
        assertThat(reloaded.getOrgId()).isEqualTo(org.getId());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));

        // orgId-only change keeps the role and does NOT bump token_version
        postJson("/api/v1/admin/orgs", sysAdminToken, Map.of("name", "이관 기관", "slug", "adm-org-x2"))
                .andExpect(status().isCreated());
        long secondOrgId = orgRepository.findBySlug("adm-org-x2").orElseThrow().getId();
        patchJson("/api/v1/admin/users/" + target.getId(), sysAdminToken, Map.of("orgId", secondOrgId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"));
        reloaded = userRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isEqualTo(versionBefore + 1);
        assertThat(reloaded.getOrgId()).isEqualTo(secondOrgId);

        // user.role_update audit rows
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'user.role_update' and target_id = ?",
                Long.class, target.getId());
        assertThat(audits).isGreaterThanOrEqualTo(2);
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions patchJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(patch(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private User ensureUser(String email, String name, UserRole role, Long orgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setRole(role);
            user.setOrgId(orgId);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
