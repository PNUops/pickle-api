package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentStore;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionWorkspaceOwnerWorkTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private ObjectMapper json;
    @Autowired private TransactionTemplate transactions;
    private long institutionA;
    private long institutionB;
    private long workspace;
    private long outside;
    private User firstOwner;
    private User secondOwner;
    private User member;

    @BeforeEach
    void actualTargets() {
        institutionA = institution(); institutionB = institution();
        workspace = workspace("PROJECT"); outside = workspace("PROJECT");
        firstOwner = user(UserRole.USER); secondOwner = user(UserRole.USER); member = user(UserRole.USER);
        membership(workspace, firstOwner, "OWNER"); membership(workspace, secondOwner, "OWNER"); membership(workspace, member, "MEMBER");
    }

    @Test
    void ownerOnlyDetailProvidesIndependentFlagsAndMinimalMemberReferences() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspace);
        var response = detail(actor, workspace).andExpect(status().isOk()).andReturn();
        var value = json.readValue(response.getResponse().getContentAsString(), kr.ac.pusan.pickle.admin.dto.AdminWorkspaceDetailResponse.class);
        var ownerReference = value.members().stream().filter(row -> row.userId().equals(firstOwner.getPublicId())).findFirst().orElseThrow();
        var memberReference = value.members().stream().filter(row -> row.userId().equals(member.getPublicId())).findFirst().orElseThrow();
        assertAll("independent owner controls with minimal references",
                () -> assertThat(value.metadataVisible()).isFalse(),
                () -> assertThat(value.memberCount()).isNull(), () -> assertThat(value.vmCount()).isNull(),
                () -> assertThat(value.canManageMembers()).isFalse(), () -> assertThat(value.canManageOwners()).isTrue(),
                () -> assertThat(value.canInviteMembers()).isFalse(), () -> assertThat(value.canDelete()).isFalse(),
                () -> assertThat(ownerReference.email()).isNull(),
                () -> assertThat(ownerReference.canChangeRole()).isTrue(), () -> assertThat(ownerReference.canRemove()).isTrue(),
                () -> assertThat(memberReference.canChangeRole()).isTrue(), () -> assertThat(memberReference.canRemove()).isFalse());
    }

    @Test
    void ownerOnlyPreviewPromotesAndDemotesAnExistingMember() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspace);
        preview(actor, workspace, member, "OWNER").andExpect(status().isOk());
        change(actor, workspace, member, Map.of("role", "OWNER")).andExpect(status().isOk());
        assertThat(role(workspace, member)).isEqualTo("OWNER");
        preview(actor, workspace, member, "MEMBER").andExpect(status().isOk());
        change(actor, workspace, member, Map.of("role", "MEMBER")).andExpect(status().isOk());
        assertThat(role(workspace, member)).isEqualTo("MEMBER");
    }

    @Test
    void ownerOnlyRemovesAnOwnerWhileAnotherOwnerRemains() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspace);
        remove(actor, workspace, secondOwner).andExpect(status().isNoContent());
        assertThat(memberCount(workspace, secondOwner)).isZero();
        assertThat(ownerCount(workspace)).isEqualTo(1);
    }

    @Test
    void memberOnlyRemovesAnOrdinaryMember() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_MEMBERS_MANAGE), workspace);
        remove(actor, workspace, member).andExpect(status().isNoContent());
        assertThat(memberCount(workspace, member)).isZero();
        assertThat(ownerCount(workspace)).isEqualTo(2);
    }

    @Test
    void memberOnlyDoesNotAcquireOwnerCommands() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_MEMBERS_MANAGE), workspace);
        preview(actor, workspace, member, "OWNER").andExpect(status().isForbidden());
        change(actor, workspace, member, Map.of("role", "OWNER")).andExpect(status().isForbidden());
        remove(actor, workspace, secondOwner).andExpect(status().isForbidden());
        assertThat(role(workspace, member)).isEqualTo("MEMBER");
        assertThat(ownerCount(workspace)).isEqualTo(2);
    }

    @Test
    void ownerOnlyDoesNotAcquireOrdinaryMemberRemoval() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspace);
        remove(actor, workspace, member).andExpect(status().isForbidden());
        assertThat(memberCount(workspace, member)).isEqualTo(1);
    }

    @Test
    void aMemberSourceCannotLendItsWorkspaceToAnOwnerSource() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), outside);
        grant(actor, institutionB, Set.of(InstitutionWork.WORKSPACE_MEMBERS_MANAGE), workspace);
        detail(actor, workspace).andExpect(status().isOk()).andExpect(jsonPath("$.canManageOwners").value(false));
        preview(actor, workspace, member, "OWNER").andExpect(status().isForbidden());
        change(actor, workspace, member, Map.of("role", "OWNER")).andExpect(status().isForbidden());
        assertThat(role(workspace, member)).isEqualTo("MEMBER");
    }

    @Test
    void anIndependentOwnerSourceNeedsNoMemberSourceIntersection() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspace);
        grant(actor, institutionB, Set.of(InstitutionWork.WORKSPACE_MEMBERS_MANAGE), workspace);
        preview(actor, workspace, member, "OWNER").andExpect(status().isOk());
        change(actor, workspace, member, Map.of("role", "OWNER")).andExpect(status().isOk());
        assertThat(role(workspace, member)).isEqualTo("OWNER");
    }

    @Test
    void ownerOnlyPreservesTheLastOwnerProtection() throws Exception {
        jdbc.update("delete from workspace_members where workspace_id=? and user_id=?", workspace, secondOwner.getId());
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspace);
        change(actor, workspace, firstOwner, Map.of("role", "MEMBER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKSPACE_SOLE_OWNER_REMOVAL"));
        remove(actor, workspace, firstOwner).andExpect(status().isConflict());
        assertThat(role(workspace, firstOwner)).isEqualTo("OWNER");
    }

    @Test
    void personalWorkspaceStructureRemainsProtected() throws Exception {
        long personal = workspace("PERSONAL"); membership(personal, firstOwner, "OWNER");
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE, InstitutionWork.WORKSPACE_MEMBERS_MANAGE), personal);
        preview(actor, personal, firstOwner, "MEMBER").andExpect(status().isForbidden());
        change(actor, personal, firstOwner, Map.of("role", "MEMBER")).andExpect(status().isForbidden());
        remove(actor, personal, firstOwner).andExpect(status().isForbidden());
        assertThat(role(personal, firstOwner)).isEqualTo("OWNER");
    }

    @Test
    void selfPromotionStillRequiresPurposeAndTheCurrentImpact() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspace);
        membership(workspace, actor, "MEMBER");
        var observed = preview(actor, workspace, actor, "OWNER").andExpect(status().isOk()).andReturn();
        String fingerprint = json.readTree(observed.getResponse().getContentAsString()).get("fingerprint").asString();
        change(actor, workspace, actor, Map.of("role", "OWNER"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("confirmation"));
        assertThat(role(workspace, actor)).isEqualTo("MEMBER");
        change(actor, workspace, actor, Map.of("role", "OWNER", "confirmation",
                Map.of("purpose", "", "fingerprint", fingerprint, "confirmed", true))).andExpect(status().isUnprocessableContent());
        change(actor, workspace, actor, Map.of("role", "OWNER", "confirmation",
                Map.of("purpose", "워크스페이스 소유 업무 수행", "fingerprint", fingerprint, "confirmed", true)))
                .andExpect(status().isOk());
        assertThat(role(workspace, actor)).isEqualTo("OWNER");
    }

    @Test
    void systemAccountMembershipIsNotAGlobalRoleChange() throws Exception {
        User actor = actor(Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspace);
        User systemMember = user(UserRole.SYS_MANAGER); membership(workspace, systemMember, "MEMBER");
        preview(actor, workspace, systemMember, "OWNER").andExpect(status().isOk());
        change(actor, workspace, systemMember, Map.of("role", "OWNER")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select role::text from users where id=?", String.class, systemMember.getId())).isEqualTo("SYS_MANAGER");
        remove(actor, workspace, systemMember).andExpect(status().isNoContent());
        assertThat(memberCount(workspace, systemMember)).isZero();
        assertThat(jdbc.queryForObject("select role::text from users where id=?", String.class, systemMember.getId())).isEqualTo("SYS_MANAGER");
    }

    @Test
    void originalSystemAdministratorExceptionDoesNotExpandToSystemManager() throws Exception {
        User systemManager = user(UserRole.SYS_MANAGER);
        preview(systemManager, workspace, member, "OWNER").andExpect(status().isForbidden());
        User systemAdmin = users.findById(SeedFixtures.sysadminId(jdbc)).orElseThrow();
        preview(systemAdmin, workspace, member, "OWNER").andExpect(status().isOk());
        change(systemAdmin, workspace, member, Map.of("role", "OWNER")).andExpect(status().isOk());
        assertThat(role(workspace, member)).isEqualTo("OWNER");
    }

    private User actor(Set<InstitutionWork> work, long scopeWorkspace) {
        User actor = user(UserRole.USER); grant(actor, institutionA, work, scopeWorkspace); return actor;
    }
    private void grant(User actor, long institution, Set<InstitutionWork> work, long scopeWorkspace) {
        SeedFixtures.grantOrgRole(jdbc, actor.getId(), institution, UserRole.ORG_MANAGER);
        long revision = jdbc.queryForObject("select permission_revision from user_org_roles where user_id=? and org_id=?", Long.class,
                actor.getId(), institution);
        var assignment = InstitutionAssignment.selected(actor.getPublicId(), publicId("orgs", institution), work,
                Map.of(InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", scopeWorkspace)))), 0);
        transactions.executeWithoutResult(status -> new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec()).save(assignment, revision));
    }
    private User user(UserRole role) {
        User user = new User("owner-work-" + UUID.randomUUID() + "@example.test", "{noop}unused", "합성 공간 담당자");
        user.setRole(role); user.setStatus(UserStatus.ACTIVE); user.setEmailVerifiedAt(Instant.now()); return users.saveAndFlush(user);
    }
    private long institution() { return jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "Owner source " + UUID.randomUUID()); }
    private long workspace(String kind) { return jdbc.queryForObject("insert into workspaces(kind,name) values(?::workspace_kind,?) returning id", Long.class, kind, "Owner work " + UUID.randomUUID()); }
    private void membership(long target, User user, String role) { jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,?::workspace_member_role)", target, user.getId(), role); }
    private String bearer(User user) { return "Bearer " + jwt.createAccessToken(users.findById(user.getId()).orElseThrow()); }
    private String path(long target) { return "/api/v1/admin/workspaces/" + publicId("workspaces", target); }
    private ResultActions detail(User actor, long target) throws Exception { return mvc.perform(get(path(target)).header("Authorization", bearer(actor))); }
    private ResultActions preview(User actor, long target, User member, String role) throws Exception {
        return mvc.perform(post(path(target) + "/members/" + member.getPublicId() + "/preview").header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", role))));
    }
    private ResultActions change(User actor, long target, User member, Map<String, Object> body) throws Exception {
        return mvc.perform(patch(path(target) + "/members/" + member.getPublicId()).header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    private ResultActions remove(User actor, long target, User member) throws Exception {
        return mvc.perform(delete(path(target) + "/members/" + member.getPublicId()).header("Authorization", bearer(actor)));
    }
    private UUID publicId(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
    private String role(long target, User user) { return jdbc.queryForObject("select role::text from workspace_members where workspace_id=? and user_id=?", String.class, target, user.getId()); }
    private long memberCount(long target, User user) { return jdbc.queryForObject("select count(*) from workspace_members where workspace_id=? and user_id=?", Long.class, target, user.getId()); }
    private long ownerCount(long target) { return jdbc.queryForObject("select count(*) from workspace_members where workspace_id=? and role='OWNER'", Long.class, target); }
}
