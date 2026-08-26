package kr.ac.pusan.pickle.admin;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Admin workspace read surface (contract v0.19.0): the option list's additive
 * {@code kind}/{@code createdAt} fields and the new inspection detail —
 * members listed regardless of account status, non-DELETED VM count, and the
 * admin 404 mask (unknown / soft-deleted / cross-org all identical).
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminWorkspacesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long workspaceId;
    private String slug;
    private String sysAdminToken;
    private String orgAdminToken;
    private long ownerId;
    private long disabledMemberId;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        slug = "agr-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name, description)
                values ('TEAM', ?, '워크스페이스 조회 테스트') returning id
                """, Long.class, slug);
        ownerId = ensureUser("agr.owner." + slug + "@pusan.ac.kr", UserStatus.ACTIVE).getId();
        disabledMemberId = ensureUser("agr.off." + slug + "@pusan.ac.kr", UserStatus.DISABLED).getId();
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'OWNER'::workspace_member_role), (?, ?, 'MEMBER'::workspace_member_role)
                """, workspaceId, ownerId, workspaceId, disabledMemberId);
        // link the workspace to the seed org (derived membership: ≥1 request in the org)
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, ownerId, "워크스페이스 조회 테스트", imageId, 1, 1024, 10);
    }

    @Test
    void optionListCarriesKindAndCreatedAt() throws Exception {
        mockMvc.perform(get("/api/v1/admin/workspaces")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(bySlug() + ".kind").value("TEAM"))
                .andExpect(jsonPath(bySlug() + ".createdAt").isNotEmpty())
                // memberCount keeps its ACTIVE-only definition (fan-out basis)
                .andExpect(jsonPath(bySlug() + ".memberCount").value(1));
    }

    @Test
    void detailListsEveryMemberWithAccountStatusAndVmCount() throws Exception {
        mockMvc.perform(get("/api/v1/admin/workspaces/{id}", pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(slug))
                .andExpect(jsonPath("$.kind").value("TEAM"))
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.vmCount").value(0))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].workspaceRole").value("OWNER"))
                .andExpect(jsonPath("$.members[?(@.userId == '%s')].workspaceRole"
                        .formatted(pub("users", ownerId)))
                        .value("OWNER"))
                .andExpect(jsonPath("$.members[?(@.userId == '%s')].userStatus"
                        .formatted(pub("users", disabledMemberId))).value("DISABLED"));
    }

    @Test
    void unknownDeletedAndCrossOrgWorkspacesAnswerTheSame404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/workspaces/" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isNotFound());

        // soft-deleted → 404 for everyone
        String deletedSlug = "agr-del-" + UUID.randomUUID().toString().substring(0, 8);
        long deleted = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name, deleted_at, deleted_by)
                values ('TEAM', ?, now(), ?) returning id
                """, Long.class, deletedSlug, ownerId);
        mockMvc.perform(get("/api/v1/admin/workspaces/{id}", pub("workspaces", deleted))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isNotFound());

        // a workspace with no request/VM in the admin's org → 404 for the org tier
        String foreignSlug = "agr-for-" + UUID.randomUUID().toString().substring(0, 8);
        long unlinked = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, foreignSlug);
        mockMvc.perform(get("/api/v1/admin/workspaces/{id}", pub("workspaces", unlinked))
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound());
        // ...but the sys tier still sees it
        mockMvc.perform(get("/api/v1/admin/workspaces/{id}", pub("workspaces", unlinked))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk());

        // a plain user is refused by the role gate
        String userToken = jwtService.createAccessToken(
                ensureUser("agr.user." + slug + "@pusan.ac.kr", UserStatus.ACTIVE));
        mockMvc.perform(get("/api/v1/admin/workspaces/{id}", pub("workspaces", workspaceId))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private String bySlug() {
        return "$[?(@.name == '%s')]".formatted(slug);
    }

    private User ensureUser(String email, UserStatus status) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "워크스페이스조회테스트");
            user.setRole(UserRole.USER);
            user.setStatus(status);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
