package kr.ac.pusan.pickle.announcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AnnouncementPreviewTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private OrgRepository orgs;

    private Org ownOrg;
    private Org otherOrg;
    private User author;
    private User manager;
    private User viewer;
    private User member;
    private User crossMember;
    private User inactive;
    private User sys;
    private long workspace;

    @BeforeEach
    void setUp() {
        ownOrg = orgs.saveAndFlush(new Org("발송 미리보기 " + UUID.randomUUID(), null));
        otherOrg = orgs.saveAndFlush(new Org("다른 발송 기관 " + UUID.randomUUID(), null));
        author = user(UserRole.ORG_ADMIN);
        manager = user(UserRole.ORG_MANAGER);
        viewer = user(UserRole.ORG_VIEWER);
        member = user(UserRole.USER);
        crossMember = user(UserRole.USER);
        inactive = user(UserRole.USER);
        sys = user(UserRole.SYS_ADMIN);
        grant(author, ownOrg, UserRole.ORG_ADMIN);
        grant(manager, ownOrg, UserRole.ORG_MANAGER);
        grant(viewer, ownOrg, UserRole.ORG_VIEWER);
        jdbc.update("update users set status = 'DISABLED' where id = ?", inactive.getId());
        workspace = workspace(member, crossMember, inactive);
        link(workspace, ownOrg, member);
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void previewUsesTheExistingAuthoringRoles(UserRole role) throws Exception {
        User actor = user(role);
        if (role.isOrgTier()) grant(actor, ownOrg, role);
        var response = preview(actor, form("ORG", ownOrg.getPublicId(), null));
        if (role == UserRole.SYS_ADMIN || role == UserRole.ORG_ADMIN) response.andExpect(status().isOk());
        else response.andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void detailUsesTheSameSixReadRolesAsTheList(UserRole role) throws Exception {
        UUID id = send(sys, form("ALL", null, null));
        User actor = user(role);
        if (role.isOrgTier()) grant(actor, ownOrg, role);
        var response = detail(actor, id);
        if (role == UserRole.USER) response.andExpect(status().isForbidden());
        else response.andExpect(status().isOk()).andExpect(jsonPath("$.body").value("합성 발송 본문"));
    }

    @Test
    void scopeChecksUseTheRoleHeldInTheActualOrganisation() throws Exception {
        grant(author, otherOrg, UserRole.ORG_MANAGER);
        long otherWorkspace = workspace(crossMember);
        link(otherWorkspace, otherOrg, crossMember);
        preview(author, form("ALL", null, null)).andExpect(status().isForbidden());
        preview(author, form("ORG", otherOrg.getPublicId(), null)).andExpect(status().isUnprocessableEntity());
        preview(author, form("WORKSPACE", null, publicId("workspaces", otherWorkspace))).andExpect(status().isNotFound());
        preview(author, form("ORG", SeedFixtures.UNKNOWN_ID, null)).andExpect(status().isUnprocessableEntity());
        preview(author, form("WORKSPACE", null, SeedFixtures.UNKNOWN_ID)).andExpect(status().isNotFound());
        preview(author, form("ALL", ownOrg.getPublicId(), null)).andExpect(status().isForbidden());
        preview(sys, form("ALL", ownOrg.getPublicId(), null)).andExpect(status().isUnprocessableEntity());
        preview(author, form("ORG", null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(ownOrg.getPublicId().toString()));
    }

    @Test
    void institutionAndWorkspacePreviewsReuseTheirExistingRecipientSemantics() throws Exception {
        grant(crossMember, otherOrg, UserRole.ORG_MANAGER);
        JsonNode orgPreview = json(preview(author, form("ORG", ownOrg.getPublicId(), null)).andExpect(status().isOk()));
        assertThat(orgPreview.get("recipientCount").asInt()).isEqualTo(4);
        assertThat(orgPreview.get("sample").toString()).contains(author.getPublicId().toString(), manager.getPublicId().toString(),
                member.getPublicId().toString(), crossMember.getPublicId().toString())
                .doesNotContain(viewer.getPublicId().toString(), inactive.getPublicId().toString());
        preview(author, form("WORKSPACE", null, publicId("workspaces", workspace))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(2));
        UUID sent = send(author, form("ORG", ownOrg.getPublicId(), null));
        assertThat(jdbc.queryForObject("select recipient_count from announcements where public_id = ?", Integer.class, sent))
                .isEqualTo(orgPreview.get("recipientCount").asInt());
    }

    @Test
    void anInstitutionPreviewHidesSystemAccountsWithoutChangingItsAudience() throws Exception {
        addMember(workspace, sys);
        UUID workspaceId = publicId("workspaces", workspace);
        JsonNode preview = json(preview(author, form("WORKSPACE", null, workspaceId)).andExpect(status().isOk()));
        assertThat(preview.get("recipientCount").asInt()).isEqualTo(3);
        assertThat(preview.get("sample").toString()).contains(member.getPublicId().toString(), crossMember.getPublicId().toString())
                .doesNotContain(sys.getPublicId().toString(), sys.getEmail());
        assertThat(preview.get("sample").size()).isEqualTo(2);
        assertThat(preview.get("truncated").asBoolean()).isTrue();
        assertThat(preview.get("warnings").size()).isEqualTo(1);
        JsonNode orgPreview = json(preview(author, form("ORG", ownOrg.getPublicId(), null)).andExpect(status().isOk()));
        assertThat(orgPreview.get("recipientCount").asInt()).isEqualTo(5);
        assertThat(orgPreview.get("sample").toString()).doesNotContain(sys.getPublicId().toString(), sys.getEmail());
        JsonNode systemPreview = json(preview(sys, form("WORKSPACE", null, workspaceId)).andExpect(status().isOk()));
        assertThat(systemPreview.get("sample").toString()).contains(sys.getPublicId().toString(), sys.getEmail());
        assertThat(systemPreview.get("sample").size()).isEqualTo(3);
        assertThat(systemPreview.get("truncated").asBoolean()).isFalse();
        assertThat(systemPreview.get("warnings").size()).isZero();
        UUID id = send(author, form("WORKSPACE", null, workspaceId));
        assertThat(jdbc.queryForObject("select count(*) from notifications n join announcements a on a.id = n.announcement_id "
                + "where a.public_id = ?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from mail_deliveries where announcement_id = ? and recipient_user_id = ?",
                Integer.class, id, sys.getPublicId())).isEqualTo(1);
    }

    @Test
    void previewSampleIsLimitedAndZeroRecipientsRemainAllowed() throws Exception {
        long many = workspace();
        for (int i = 0; i < 25; i++) addMember(many, user(UserRole.USER));
        preview(sys, form("WORKSPACE", null, publicId("workspaces", many))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(25))
                .andExpect(jsonPath("$.sample.length()").value(20))
                .andExpect(jsonPath("$.truncated").value(true));
        Org empty = orgs.saveAndFlush(new Org("빈 발송 기관 " + UUID.randomUUID(), null));
        preview(sys, form("ORG", empty.getPublicId(), null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(0))
                .andExpect(jsonPath("$.sample.length()").value(0))
                .andExpect(jsonPath("$.warnings.length()").value(1));
        UUID id = send(sys, form("ORG", empty.getPublicId(), null));
        detail(sys, id).andExpect(status().isOk()).andExpect(jsonPath("$.recipientCount").value(0));
        assertThat(jdbc.queryForObject("select count(*) from notifications n join announcements a on a.id = n.announcement_id "
                + "where a.public_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void previewsDoNotPersistOrSpendTheAuthorsSendBudget() throws Exception {
        long beforeAnnouncements = count("announcements");
        long beforeNotifications = count("notifications");
        long beforeEvidence = count("mail_deliveries");
        for (int i = 0; i < 12; i++) preview(author, form("ORG", ownOrg.getPublicId(), null)).andExpect(status().isOk());
        assertThat(count("announcements")).isEqualTo(beforeAnnouncements);
        assertThat(count("notifications")).isEqualTo(beforeNotifications);
        assertThat(count("mail_deliveries")).isEqualTo(beforeEvidence);
        assertThat(jdbc.queryForObject("select count(*) from auth_rate_limits where scope = 'announce' and subject = ?",
                Integer.class, author.getId().toString())).isZero();
        for (int i = 0; i < 10; i++) send(author, form("ORG", ownOrg.getPublicId(), null));
        create(author, form("ORG", ownOrg.getPublicId(), null)).andExpect(status().isTooManyRequests());
    }

    @Test
    void sendResolvesCurrentRecipientsOnceAndKeepsTheResultAfterLaterAccountChanges() throws Exception {
        UUID workspaceId = publicId("workspaces", workspace);
        preview(sys, form("WORKSPACE", null, workspaceId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(2));
        String replacement = "replacement." + UUID.randomUUID() + "@pusan.ac.kr";
        jdbc.update("update users set status = 'DISABLED' where id = ?", member.getId());
        jdbc.update("update users set email = ? where id = ?", replacement, crossMember.getId());
        jdbc.update("update users set status = 'ACTIVE' where id = ?", inactive.getId());
        UUID sent = send(sys, form("WORKSPACE", null, workspaceId));
        var addresses = jdbc.queryForList("select recipient_email from notifications n join announcements a "
                + "on a.id = n.announcement_id where a.public_id = ? order by recipient_email", String.class, sent);
        assertThat(addresses).containsExactlyInAnyOrder(replacement, inactive.getEmail());
        assertThat(jdbc.queryForList("select recipient_email from mail_deliveries where announcement_id = ?", String.class, sent))
                .containsExactlyInAnyOrderElementsOf(addresses);
        jdbc.update("update users set status = 'DISABLED', email = ? where id = ?", address(), crossMember.getId());
        detail(sys, sent).andExpect(status().isOk()).andExpect(jsonPath("$.recipientCount").value(2))
                .andExpect(jsonPath("$.scope").value("WORKSPACE"))
                .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()));
        assertThat(jdbc.queryForList("select recipient_email from mail_deliveries where announcement_id = ?", String.class, sent))
                .containsExactlyInAnyOrderElementsOf(addresses);
    }

    @Test
    void aJournalInsertFailureRollsBackTheAnnouncementAndItsFanout() throws Exception {
        String title = "원자 발송 " + UUID.randomUUID();
        String function = "reject_announcement_evidence_" + UUID.randomUUID().toString().replace("-", "");
        String trigger = function + "_trigger";
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $$ begin "
                + "if new.title = '" + title + "' then raise exception 'synthetic evidence failure'; end if; return new; end $$");
        jdbc.execute("create trigger " + trigger + " before insert on mail_deliveries for each row execute function " + function + "()");
        try {
            create(author, Map.of("scope", "ORG", "orgId", ownOrg.getPublicId(), "title", title, "body", "합성 본문"))
                    .andExpect(status().isInternalServerError());
            assertThat(jdbc.queryForObject("select count(*) from announcements where title = ?", Integer.class, title)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from notifications where title = ?", Integer.class, title)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from mail_deliveries where title = ?", Integer.class, title)).isZero();
        } finally {
            jdbc.execute("drop trigger " + trigger + " on mail_deliveries");
            jdbc.execute("drop function " + function + "()");
        }
    }

    @Test
    void changesAfterAudienceSelectionCannotAlterTheQueuedSnapshot() throws Exception {
        String title = "단일 대상 선정 " + UUID.randomUUID();
        String replacement = address();
        String function = "change_after_audience_" + UUID.randomUUID().toString().replace("-", "");
        String trigger = function + "_trigger";
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $$ begin "
                + "if new.title = '" + title + "' then "
                + "update users set status = 'DISABLED' where id = " + member.getId() + "; "
                + "update users set email = '" + replacement + "' where id = " + crossMember.getId() + "; "
                + "update users set status = 'ACTIVE' where id = " + inactive.getId() + "; "
                + "end if; return new; end $$");
        jdbc.execute("create trigger " + trigger + " before insert on announcements for each row execute function " + function + "()");
        try {
            UUID id = send(sys, Map.of("scope", "WORKSPACE", "workspaceId", publicId("workspaces", workspace),
                    "title", title, "body", "합성 본문"));
            assertThat(jdbc.queryForList("select u.public_id from notifications n join users u on u.id = n.user_id "
                    + "join announcements a on a.id = n.announcement_id where a.public_id = ?", UUID.class, id))
                    .containsExactlyInAnyOrder(member.getPublicId(), crossMember.getPublicId());
            assertThat(jdbc.queryForList("select recipient_email from mail_deliveries where announcement_id = ?", String.class, id))
                    .containsExactlyInAnyOrder(member.getEmail(), crossMember.getEmail());
            detail(sys, id).andExpect(status().isOk()).andExpect(jsonPath("$.recipientCount").value(2));
        } finally {
            jdbc.execute("drop trigger " + trigger + " on announcements");
            jdbc.execute("drop function " + function + "()");
        }
    }

    @Test
    void detailAndListFollowTheSameCurrentAuthorOrganisationVisibility() throws Exception {
        UUID id = send(author, form("ORG", ownOrg.getPublicId(), null));
        User otherViewer = user(UserRole.ORG_VIEWER);
        grant(otherViewer, otherOrg, UserRole.ORG_VIEWER);
        detail(viewer, id).andExpect(status().isOk());
        detail(otherViewer, id).andExpect(status().isNotFound());
        assertThat(listed(viewer, id)).isTrue();
        assertThat(listed(otherViewer, id)).isFalse();
        grant(author, otherOrg, UserRole.ORG_VIEWER);
        detail(otherViewer, id).andExpect(status().isOk()).andExpect(jsonPath("$.orgId").value(ownOrg.getPublicId().toString()));
        assertThat(listed(otherViewer, id)).isTrue();
        jdbc.update("delete from user_org_roles where user_id = ? and org_id = ?", author.getId(), ownOrg.getId());
        detail(viewer, id).andExpect(status().isNotFound());
        assertThat(listed(viewer, id)).isFalse();
        detail(otherViewer, id).andExpect(status().isOk()).andExpect(jsonPath("$.recipientCount").value(4));
    }

    @Test
    void aSystemAuthorsInstitutionSendIsNotMadeVisibleByTheNewDetail() throws Exception {
        UUID id = send(sys, form("ORG", ownOrg.getPublicId(), null));
        detail(viewer, id).andExpect(status().isNotFound());
        assertThat(listed(viewer, id)).isFalse();
        detail(user(UserRole.SYS_VIEWER), id).andExpect(status().isOk());
        detail(sys, SeedFixtures.UNKNOWN_ID).andExpect(status().isNotFound());
    }

    private ResultActions preview(User actor, Map<String, Object> form) throws Exception {
        return mvc.perform(post("/api/v1/admin/announcements/preview").header("Authorization", token(actor))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(form)));
    }

    private ResultActions create(User actor, Map<String, Object> form) throws Exception {
        return mvc.perform(post("/api/v1/admin/announcements").header("Authorization", token(actor))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(form)));
    }

    private UUID send(User actor, Map<String, Object> form) throws Exception {
        return UUID.fromString(json(create(actor, form).andExpect(status().isCreated())).get("id").asString());
    }

    private ResultActions detail(User actor, UUID id) throws Exception {
        return mvc.perform(get("/api/v1/admin/announcements/" + id).header("Authorization", token(actor)));
    }

    private boolean listed(User actor, UUID id) throws Exception {
        JsonNode items = json(mvc.perform(get("/api/v1/admin/announcements").param("size", "100")
                .header("Authorization", token(actor))).andExpect(status().isOk())).get("content");
        for (JsonNode item : items) if (id.toString().equals(item.get("id").asString())) return true;
        return false;
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> form(String scope, UUID orgId, UUID workspaceId) {
        var values = new java.util.HashMap<String, Object>();
        values.put("title", "합성 발송 제목");
        values.put("body", "합성 발송 본문");
        values.put("scope", scope);
        if (orgId != null) values.put("orgId", orgId);
        if (workspaceId != null) values.put("workspaceId", workspaceId);
        return values;
    }

    private void grant(User user, Org org, UserRole role) {
        SeedFixtures.grantOrgRole(jdbc, user.getId(), org.getId(), role);
    }

    private long workspace(User... members) {
        long id = jdbc.queryForObject("insert into workspaces(kind,name) values ('PROJECT', ?) returning id", Long.class,
                "합성 대상 " + UUID.randomUUID());
        for (User user : members) addMember(id, user);
        return id;
    }

    private void addMember(long workspaceId, User user) {
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values (?,?,'MEMBER')", workspaceId, user.getId());
    }

    private void link(long workspaceId, Org org, User requester) {
        RequestFixtures.insertVmRequest(jdbc, workspaceId, org.getId(), requester.getId(), "합성 연결", null, 1, 1024, 20);
    }

    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    private UUID publicId(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
    private String token(User user) { return "Bearer " + jwt.createAccessToken(user); }

    private User user(UserRole role) {
        User user = new User(address(), "{test-no-login}", "합성 " + role);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private static String address() { return "preview." + UUID.randomUUID() + "@pusan.ac.kr"; }
}
