package kr.ac.pusan.pickle.announcement;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Announcements + the workspace picker per contract v0.5.0: scope rules (ALL is
 * SYS_ADMIN-only 403; ORG pinned for ORG_ADMIN with mismatch 422; the WORKSPACE
 * gate and zero-link 404), synchronous fan-out counts over ACTIVE users,
 * sender-org list visibility, the per-author 10/hour rate budget
 * (429 + Retry-After), and {@code GET /admin/workspaces} scoping.
 *
 * <p><b>Org-membership semantics</b> (operator decision): regular users carry
 * no {@code users.org_id} (V11 {@code chk_users_org_role}); a user belongs to
 * an org iff they are an ACTIVE member of a workspace with ≥1 request or
 * non-DELETED VM in that org, or an ORG_ADMIN of it. The WORKSPACE scope is gated
 * on the workspace having resources in the caller's org and then reaches
 * <b>all</b> the workspace's ACTIVE members.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AnnouncementTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Org org;
    private Org otherOrg;
    private User ownMember;
    private User inactiveMember;
    private User crossMember;
    private User farMember;
    private String sysAdminToken;
    private String orgAdminToken;
    private String otherOrgAdminToken;
    private String userToken;
    private long mixedWorkspaceId;
    private long foreignWorkspaceId;

    @BeforeEach
    void setUp() {
        org = orgRepository.findFirstByNameOrderByIdAsc(SeedFixtures.ORG_NAME).orElseThrow();
        otherOrg = orgRepository.findFirstByNameOrderByIdAsc("공지 타기관").orElseGet(() ->
                orgRepository.save(new Org("공지 타기관", null)));
        ownMember = ensureRegularUser("ann.own.member@pusan.ac.kr", "공지자기관원", UserStatus.ACTIVE);
        inactiveMember = ensureRegularUser("ann.pending@pusan.ac.kr", "공지비활성",
                UserStatus.DISABLED);
        crossMember = ensureRegularUser("ann.cross.member@pusan.ac.kr", "공지동반원",
                UserStatus.ACTIVE);
        farMember = ensureRegularUser("ann.far.member@pusan.ac.kr", "공지타기관원", UserStatus.ACTIVE);
        User otherOrgAdmin = userRepository.findByEmail("ann.other.admin@pusan.ac.kr")
                .orElseGet(() -> userRepository.save(
                        new User("ann.other.admin@pusan.ac.kr", "{noop}unused", "공지타기관장")));
        otherOrgAdmin.setRole(UserRole.ORG_ADMIN);
        otherOrgAdmin.setOrgId(otherOrg.getId());
        otherOrgAdmin.setStatus(UserStatus.ACTIVE);
        otherOrgAdmin = userRepository.save(otherOrgAdmin);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        otherOrgAdminToken = jwtService.createAccessToken(otherOrgAdmin);
        userToken = jwtService.createAccessToken(ownMember);
        // workspace linked to the caller's org (request), ACTIVE + DISABLED members
        mixedWorkspaceId = createWorkspace("annmix", ownMember.getId(), inactiveMember.getId(),
                crossMember.getId());
        linkWorkspaceToOrg(mixedWorkspaceId, org.getId(), ownMember.getId());
        // workspace linked only to the other org — never targetable by our ORG_ADMIN
        foreignWorkspaceId = createWorkspace("annfor", farMember.getId());
        linkWorkspaceToOrg(foreignWorkspaceId, otherOrg.getId(), farMember.getId());
        jdbcTemplate.update("delete from auth_rate_limits where scope = 'announce'");
    }

    @Test
    void scopeRulesGateAllOrgAndWorkspaceSends() throws Exception {
        // users never reach the endpoint
        create(userToken, Map.of("title", "t", "body", "b", "scope", "ALL"))
                .andExpect(status().isForbidden());
        // ALL is SYS_ADMIN-only
        create(orgAdminToken, Map.of("title", "t", "body", "b", "scope", "ALL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        // scope/target cross-field mismatches → 422
        create(sysAdminToken, Map.of("title", "t", "body", "b", "scope", "ALL",
                "workspaceId", pub("workspaces", mixedWorkspaceId))).andExpect(status().isUnprocessableContent());
        create(orgAdminToken, Map.of("title", "t", "body", "b", "scope", "ORG",
                "workspaceId", pub("workspaces", mixedWorkspaceId))).andExpect(status().isUnprocessableContent());
        create(orgAdminToken, Map.of("title", "t", "body", "b", "scope", "WORKSPACE"))
                .andExpect(status().isUnprocessableContent());
        create(orgAdminToken, Map.of("title", "t", "body", "b", "scope", "WORKSPACE",
                "workspaceId", pub("workspaces", mixedWorkspaceId), "orgId", org.getPublicId()))
                .andExpect(status().isUnprocessableContent());
        // ORG pinned: another org's id → 422
        create(orgAdminToken, Map.of("title", "t", "body", "b", "scope", "ORG",
                "orgId", otherOrg.getPublicId())).andExpect(status().isUnprocessableContent());
        // WORKSPACE without resources in the caller's org → 404 (existence
        // masked), exactly like a workspace that does not exist at all
        create(orgAdminToken, Map.of("title", "t", "body", "b", "scope", "WORKSPACE",
                "workspaceId", pub("workspaces", foreignWorkspaceId))).andExpect(status().isNotFound());
        create(orgAdminToken, Map.of("title", "t", "body", "b", "scope", "WORKSPACE",
                "workspaceId", SeedFixtures.UNKNOWN_ID)).andExpect(status().isNotFound());
    }

    @Test
    void fanOutInsertsInAppRowsSynchronouslyPerScope() throws Exception {
        // ALL: every ACTIVE user, snapshot taken right before the send
        long activeUsers = jdbcTemplate.queryForObject(
                "select count(*) from users where status = 'ACTIVE'", Long.class);
        long allId = createdId(create(sysAdminToken, Map.of(
                "title", "전체 점검 공지", "body", "본문", "scope", "ALL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("ALL"))
                .andExpect(jsonPath("$.recipientCount").value((int) activeUsers)));
        assertThat(recipientRows(allId)).isEqualTo(activeUsers);
        // the in-app rows exist at 201 time (synchronous), PENDING for email
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where announcement_id = ? and event = 'announcement' and status = 'PENDING'
                """, Long.class, allId)).isEqualTo(activeUsers);

        // ORG (pinned): derived members — workspace members via the org-linked
        // workspace and the org's ORG_ADMINs; DISABLED and other-org-only excluded
        ResultActions orgSend = create(orgAdminToken, Map.of(
                "title", "기관 공지", "body", "본문", "scope", "ORG"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orgId").value(org.getPublicId().toString()));
        long orgAnnId = createdId(orgSend);
        assertThat(recipientOf(orgAnnId, ownMember.getId())).isEqualTo(1);
        assertThat(recipientOf(orgAnnId, crossMember.getId())).isEqualTo(1);
        Long seededOrgAdminId = userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL)
                .map(User::getId).orElseThrow();
        assertThat(recipientOf(orgAnnId, seededOrgAdminId)).isEqualTo(1);
        assertThat(recipientOf(orgAnnId, inactiveMember.getId())).isZero();
        assertThat(recipientOf(orgAnnId, farMember.getId())).isZero();
        // the snapshot count equals the rows actually inserted
        assertThat(recipientRows(orgAnnId)).isEqualTo(jdbcTemplate.queryForObject("""
                select recipient_count from announcements where id = ?
                """, Long.class, orgAnnId));

        // WORKSPACE by ORG_ADMIN: the gated workspace's ACTIVE members — all of them,
        // regardless of their own (non-)org
        long workspaceAnnId = createdId(create(orgAdminToken, Map.of(
                "title", "워크스페이스 공지", "body", "본문", "scope", "WORKSPACE", "workspaceId", pub("workspaces", mixedWorkspaceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipientCount").value(2)));
        assertThat(recipientOf(workspaceAnnId, ownMember.getId())).isEqualTo(1);
        assertThat(recipientOf(workspaceAnnId, crossMember.getId())).isEqualTo(1);
        assertThat(recipientOf(workspaceAnnId, inactiveMember.getId())).isZero();

        // WORKSPACE by SYS_ADMIN: same member set (no gate)
        createdId(create(sysAdminToken, Map.of(
                "title", "워크스페이스 공지(시스템)", "body", "본문", "scope", "WORKSPACE",
                "workspaceId", pub("workspaces", foreignWorkspaceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipientCount").value(1)));

        // audit row recorded for the send
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'announcement.create' and target_id = ?
                """, Long.class, pub("announcements", allId).toString())).isEqualTo(1);
    }

    @Test
    void listVisibilityFollowsTheSenderOrg() throws Exception {
        long allId = createdId(create(sysAdminToken,
                Map.of("title", "전체", "body", "b", "scope", "ALL"))
                .andExpect(status().isCreated()));
        long ownOrgId = createdId(create(orgAdminToken,
                Map.of("title", "자기관", "body", "b", "scope", "ORG"))
                .andExpect(status().isCreated()));
        long otherOrgAnnId = createdId(create(otherOrgAdminToken,
                Map.of("title", "타기관", "body", "b", "scope", "ORG"))
                .andExpect(status().isCreated()));

        // ORG_ADMIN: own-org authors + ALL, never the other org's send
        mockMvc.perform(get("/api/v1/admin/announcements?size=100")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("announcements", allId) + "\')]").exists())
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("announcements", ownOrgId) + "\')]").exists())
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("announcements", otherOrgAnnId) + "\')]").doesNotExist());
        // SYS_ADMIN: everything
        mockMvc.perform(get("/api/v1/admin/announcements?size=100")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==\'" + pub("announcements", otherOrgAnnId) + "\')]").exists());
        // users → 403
        mockMvc.perform(get("/api/v1/admin/announcements")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void perAuthorHourlyBudgetCountsOnlyAcceptedSends() throws Exception {
        User burster = userRepository.findByEmail("ann.burst.admin@pusan.ac.kr")
                .orElseGet(() -> userRepository.save(
                        new User("ann.burst.admin@pusan.ac.kr", "{noop}unused", "공지폭주")));
        burster.setRole(UserRole.ORG_ADMIN);
        burster.setOrgId(org.getId());
        burster.setStatus(UserStatus.ACTIVE);
        burster = userRepository.save(burster);
        String bursterToken = jwtService.createAccessToken(burster);
        // rejected attempts (403/422/404) never consume the send budget —
        // the limit covers SENDS, not tries
        create(bursterToken, Map.of("title", "t", "body", "b", "scope", "ALL"))
                .andExpect(status().isForbidden());
        create(bursterToken, Map.of("title", "t", "body", "b", "scope", "WORKSPACE"))
                .andExpect(status().isUnprocessableContent());
        create(bursterToken, Map.of("title", "t", "body", "b", "scope", "WORKSPACE",
                "workspaceId", pub("workspaces", foreignWorkspaceId))).andExpect(status().isNotFound());
        for (int i = 1; i <= 10; i++) {
            create(bursterToken, Map.of("title", "공지 " + i, "body", "b", "scope", "ORG"))
                    .andExpect(status().isCreated());
        }
        create(bursterToken, Map.of("title", "공지 11", "body", "b", "scope", "ORG"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void adminWorkspacePickerFollowsTheWorkspaceGate() throws Exception {
        // ORG_ADMIN: org-linked workspaces only. memberCount counts ACTIVE members
        // (the fan-out basis) — the DISABLED member is not part of it
        mockMvc.perform(get("/api/v1/admin/workspaces")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==\'" + pub("workspaces", mixedWorkspaceId) + "\' && @.memberCount==2)]")
                        .exists())
                .andExpect(jsonPath("$[?(@.id==\'" + pub("workspaces", foreignWorkspaceId) + "\')]").doesNotExist());
        // cross-org filter → 404 (existence stays private)
        mockMvc.perform(get("/api/v1/admin/workspaces?orgId=" + otherOrg.getPublicId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound());
        // SYS_ADMIN: all workspaces without a filter; the org filter applies the gate
        mockMvc.perform(get("/api/v1/admin/workspaces")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==\'" + pub("workspaces", mixedWorkspaceId) + "\' && @.memberCount==2)]")
                        .exists())
                .andExpect(jsonPath("$[?(@.id==\'" + pub("workspaces", foreignWorkspaceId) + "\' && @.memberCount==1)]")
                        .exists());
        mockMvc.perform(get("/api/v1/admin/workspaces?orgId=" + otherOrg.getPublicId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==\'" + pub("workspaces", foreignWorkspaceId) + "\')]").exists())
                .andExpect(jsonPath("$[?(@.id==\'" + pub("workspaces", mixedWorkspaceId) + "\')]").doesNotExist());
        // users → 403
        mockMvc.perform(get("/api/v1/admin/workspaces")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ResultActions create(String token, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/announcements")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long createdId(ResultActions result) throws Exception {
        return SeedFixtures.internalId(jdbcTemplate, "announcements",
                UUID.fromString(objectMapper
                        .readTree(result.andReturn().getResponse().getContentAsString())
                        .get("id").asString()));
    }

    private long recipientRows(long announcementId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications where announcement_id = ?",
                Long.class, announcementId);
    }

    private long recipientOf(long announcementId, long userId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from notifications where announcement_id = ? and user_id = ?
                """, Long.class, announcementId, userId);
    }

    private long createWorkspace(String prefix, long... memberIds) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM', ?) returning id
                """, Long.class, slug);
        for (long memberId : memberIds) {
            jdbcTemplate.update("""
                    insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'MEMBER')
                    """, workspaceId, memberId);
        }
        return workspaceId;
    }

    /** Derived-membership link: one request of the workspace in the org. */
    private void linkWorkspaceToOrg(long workspaceId, long orgId, long requesterId) {
        RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, requesterId, "조직 연계(테스트)", null, 1, 1024, 20);
    }

    private User ensureRegularUser(String email, String name, UserStatus status) {
        User user = userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(new User(email, "{noop}unused", name)));
        user.setRole(UserRole.USER);
        user.setOrgId(null);
        user.setStatus(status);
        return userRepository.save(user);
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
