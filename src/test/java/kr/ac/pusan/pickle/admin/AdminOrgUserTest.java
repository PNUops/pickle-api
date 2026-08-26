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
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
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
    private User regularUser;
    private String sysAdminToken;
    private String orgAdminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        Org org = orgRepository.findFirstByNameOrderByIdAsc("관리 테스트 기관").orElseGet(
                () -> orgRepository.save(new Org("관리 테스트 기관", null)));
        sysAdmin = ensureUser("adm.sysadmin@pusan.ac.kr", "시스템관리자", UserRole.SYS_ADMIN, null);
        orgAdmin = ensureUser("adm.orgadmin@pusan.ac.kr", "기관관리자", UserRole.ORG_ADMIN, org.getId());
        regularUser = ensureUser("adm.user@pusan.ac.kr", "학생", UserRole.USER, null);
        sysAdminToken = jwtService.createAccessToken(sysAdmin);
        orgAdminToken = jwtService.createAccessToken(orgAdmin);
        userToken = jwtService.createAccessToken(regularUser);
    }

    @Test
    void adminOrgListShowsEveryStatusToTheSysTier() throws Exception {
        String disabledName = "관리 비활성 기관";
        Org disabled = orgRepository.findFirstByNameOrderByIdAsc(disabledName).orElseGet(
                () -> orgRepository.save(new Org(disabledName, null)));
        jdbcTemplate.update(
                "update orgs set status = 'DISABLED'::org_status, hidden = true where id = ?",
                disabled.getId());
        User sysManager = ensureUser("adm.sysmanager@pusan.ac.kr", "시스템운영자",
                UserRole.SYS_MANAGER, null);

        // DISABLED + hidden org visible with both flags (invisible on public /orgs)
        mockMvc.perform(get("/api/v1/admin/orgs")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(sysManager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(disabled.getId()) + ".status").value("DISABLED"))
                .andExpect(jsonPath(byId(disabled.getId()) + ".hidden").value(true));
        mockMvc.perform(get("/api/v1/orgs")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(disabled.getId())).doesNotExist());

        // org tier and users are refused by the role gate
        mockMvc.perform(get("/api/v1/admin/orgs")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/orgs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    private String byId(long orgId) {
        return "$[?(@.id == '%s')]".formatted(pub("orgs", orgId));
    }

    @Test
    void adminEndpointsAreSysAdminOnly() throws Exception {
        Map<String, ?> body = Map.of("name", "새 기관");
        // ORG_ADMIN and USER are rejected with 403 ACCESS_DENIED
        postJson("/api/v1/admin/orgs", orgAdminToken, body)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        postJson("/api/v1/admin/orgs", userToken, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchJson("/api/v1/admin/users/" + regularUser.getPublicId(), orgAdminToken, Map.of("role", "SYS_ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/admin/orgs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void orgCreateAndUpdateLifecycle() throws Exception {
        postJson("/api/v1/admin/orgs", sysAdminToken,
                Map.of("name", "정보컴퓨터공학부 실습지원센터", "description", "실습 자원 제공"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("정보컴퓨터공학부 실습지원센터"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        long orgId = orgRepository.findFirstByNameOrderByIdAsc("정보컴퓨터공학부 실습지원센터").orElseThrow().getId();

        // A blank name is still refused; there is no other uniqueness left to
        // check, the slug having gone with the sequential ids (V78).
        postJson("/api/v1/admin/orgs", sysAdminToken, Map.of("name", " "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        // patch name + status
        patchJson("/api/v1/admin/orgs/" + pub("orgs", orgId), sysAdminToken,
                Map.of("name", "실습지원센터(개편)", "status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("실습지원센터(개편)"))
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // DISABLED orgs disappear from the user-facing reference list
        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '실습지원센터(개편)')]").isEmpty());

        // hidden toggle (v0.15.0): back to ACTIVE but hidden — USER list still
        // filters it, manager tier sees it with hidden=true
        patchJson("/api/v1/admin/orgs/" + pub("orgs", orgId), sysAdminToken,
                Map.of("status", "ACTIVE", "hidden", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.hidden").value(true));
        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '실습지원센터(개편)')]").isEmpty());
        mockMvc.perform(get("/api/v1/orgs").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '실습지원센터(개편)')].hidden")
                        .value(org.hamcrest.Matchers.contains(true)));
        patchJson("/api/v1/admin/orgs/" + pub("orgs", orgId), sysAdminToken, Map.of("hidden", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(false));

        // empty patch → 422, unknown org → 404
        patchJson("/api/v1/admin/orgs/" + pub("orgs", orgId), sysAdminToken, Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        patchJson("/api/v1/admin/orgs/" + SeedFixtures.UNKNOWN_ID, sysAdminToken, Map.of("name", "유령 기관"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // org.create / org.update audit rows
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action in ('org.create', 'org.update') and target_id = ?",
                Long.class, pub("orgs", orgId).toString());
        assertThat(audits).isGreaterThanOrEqualTo(2);
    }

    @Test
    void userRoleUpdateValidatesOrgIdAndBumpsTokenVersion() throws Exception {
        User target = ensureUser("adm.promotee@pusan.ac.kr", "승격대상", UserRole.USER, null);
        String targetToken = jwtService.createAccessToken(target);
        int versionBefore = target.getTokenVersion();
        Org org = orgRepository.findFirstByNameOrderByIdAsc("관리 테스트 기관").orElseThrow();

        // ORG_ADMIN requires orgId → 422 with the field error
        patchJson("/api/v1/admin/users/" + target.getPublicId(), sysAdminToken, Map.of("role", "ORG_ADMIN"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("orgId"));

        // unknown orgId → 422
        patchJson("/api/v1/admin/users/" + target.getPublicId(), sysAdminToken,
                Map.of("role", "ORG_ADMIN", "orgId", SeedFixtures.UNKNOWN_ID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("orgId"));

        // USER/SYS_ADMIN must not carry an orgId → 422
        patchJson("/api/v1/admin/users/" + target.getPublicId(), sysAdminToken,
                Map.of("role", "USER", "orgId", org.getPublicId()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("orgId"));

        // empty patch → 422, unknown user → 404
        patchJson("/api/v1/admin/users/" + target.getPublicId(), sysAdminToken, Map.of())
                .andExpect(status().isUnprocessableContent());
        patchJson("/api/v1/admin/users/" + SeedFixtures.UNKNOWN_ID, sysAdminToken, Map.of("role", "USER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // promotion works and returns the contract UserSummary
        patchJson("/api/v1/admin/users/" + target.getPublicId(), sysAdminToken,
                Map.of("role", "ORG_ADMIN", "orgId", org.getPublicId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getPublicId().toString()))
                .andExpect(jsonPath("$.email").value(target.getEmail()))
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"));

        // role change bumped token_version → the old access token is dead
        User reloaded = userRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isEqualTo(versionBefore + 1);
        assertThat(SeedFixtures.managedOrgIds(jdbcTemplate, target.getId()))
                .containsExactly(org.getId());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));

        // orgId-only change keeps the role and does NOT bump token_version
        postJson("/api/v1/admin/orgs", sysAdminToken, Map.of("name", "이관 기관"))
                .andExpect(status().isCreated());
        long secondOrgId = orgRepository.findFirstByNameOrderByIdAsc("이관 기관").orElseThrow().getId();
        patchJson("/api/v1/admin/users/" + target.getPublicId(), sysAdminToken, Map.of("orgId", pub("orgs", secondOrgId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"));
        reloaded = userRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isEqualTo(versionBefore + 1);
        assertThat(SeedFixtures.managedOrgIds(jdbcTemplate, target.getId()))
                .containsExactly(secondOrgId);

        // user.role_update audit rows
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'user.role_update' and target_id = ?",
                Long.class, target.getPublicId().toString());
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
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            User saved = userRepository.save(user);
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
            return saved;
        });
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
