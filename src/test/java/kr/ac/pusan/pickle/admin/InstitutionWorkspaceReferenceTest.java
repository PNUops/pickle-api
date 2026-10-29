package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceDetailResponse;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceOptionResponse;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionActorProvider;
import kr.ac.pusan.pickle.security.institution.InstitutionResourceQueries;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionWorkspaceReferenceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private ObjectMapper json;
    @Autowired private InstitutionResourceQueries queries;
    @Autowired private InstitutionActorProvider actors;
    private long institutionA;
    private long institutionB;
    private long linkedA;
    private long linkedB;
    private long unlinked;
    private long owner;

    @BeforeEach
    void targets() {
        institutionA = institution(); institutionB = institution(); owner = user(UserRole.USER);
        linkedA = workspace(); linkedB = workspace(); unlinked = workspace();
        RequestFixtures.insertVmRequest(jdbc, linkedA, institutionA, owner, "Synthetic reference", null);
        RequestFixtures.insertVmRequest(jdbc, linkedB, institutionB, owner, "Synthetic reference", null);
    }

    @Test
    void systemAndFullMetadataRowsKeepTheirInstitutionFilter() throws Exception {
        long system = user(UserRole.SYS_ADMIN);
        long full = user(UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbc, full, institutionA, UserRole.ORG_ADMIN);
        for (long actor : List.of(system, full)) {
            var rows = list(actor, institutionB, false);
            assertThat(rows).extracting(AdminWorkspaceOptionResponse::id).containsExactly(publicId("workspaces", linkedB));
            var row = row(rows, linkedB);
            assertThat(row.metadataVisible()).isTrue(); assertThat(row.memberCount()).isEqualTo(1);
            assertThat(row.createdAt()).isNotNull();
            assertThat(rows).extracting(AdminWorkspaceOptionResponse::id).doesNotContain(publicId("workspaces", linkedA), publicId("workspaces", unlinked));
        }
    }

    @Test
    void rosterOnlyReferencesReachAnUnlinkedLiveWorkspaceWithoutMetadataOrMembership() throws Exception {
        long actor = user(UserRole.ORG_MANAGER);
        grant(actor, institutionA, Set.of(InstitutionWork.VM_ROSTER_ISSUE), Map.of(InstitutionWorkGroup.VM, listed(unlinked)));
        var rows = list(actor, institutionA, false);
        assertThat(rows).hasSize(1);
        var reference = row(rows, unlinked);
        assertThat(reference.name()).isNotBlank(); assertThat(reference.kind()).isNotNull();
        assertThat(reference.metadataVisible()).isFalse(); assertThat(reference.memberCount()).isNull(); assertThat(reference.createdAt()).isNull();
        assertThat(reference.allowedWorkIds()).containsExactly("vm.roster.issue");
        assertThat(jdbc.queryForObject("select count(*) from workspace_members where workspace_id=? and user_id=?", Long.class, unlinked, actor)).isZero();
        var detail = json.readValue(mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", unlinked))
                .header("Authorization", bearer(actor))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), AdminWorkspaceDetailResponse.class);
        assertThat(detail.members()).isNull(); assertThat(detail.memberCount()).isNull(); assertThat(detail.vmCount()).isNull();
        assertThat(detail.rosterCandidates()).singleElement().satisfies(candidate -> assertThat(candidate.userId()).isEqualTo(publicId("users", owner)));
    }

    @Test
    void metadataReadInOneWorkspaceDoesNotEraseIndependentRosterReferencesInAnother() throws Exception {
        long actor = user(UserRole.ORG_MANAGER);
        grant(actor, institutionA, Set.of(InstitutionWork.WORKSPACE_SUPPORT_READ), Map.of(InstitutionWorkGroup.WORKSPACE, listed(linkedA)));
        grant(actor, institutionB, Set.of(InstitutionWork.VM_ROSTER_ISSUE), Map.of(InstitutionWorkGroup.VM, listed(unlinked)));
        var filtered = list(actor, institutionB, false);
        assertThat(filtered).extracting(AdminWorkspaceOptionResponse::id).containsExactly(publicId("workspaces", unlinked));
        assertThat(row(filtered, unlinked).metadataVisible()).isFalse(); assertThat(row(filtered, unlinked).memberCount()).isNull();
        assertThat(row(filtered, unlinked).allowedWorkIds()).containsExactly("vm.roster.issue");
        var forA = list(actor, institutionA, true);
        assertThat(forA).extracting(AdminWorkspaceOptionResponse::id).containsExactly(publicId("workspaces", linkedA));
        assertThat(row(forA, linkedA).metadataVisible()).isTrue(); assertThat(row(forA, linkedA).memberCount()).isEqualTo(1);
        assertThat(row(forA, linkedA).allowedWorkIds()).doesNotContain("vm.roster.issue");
        var forB = list(actor, institutionB, true);
        assertThat(forB).extracting(AdminWorkspaceOptionResponse::id).containsExactlyInAnyOrder(publicId("workspaces", linkedA), publicId("workspaces", unlinked));
        assertThat(row(forB, linkedA).allowedWorkIds()).doesNotContain("vm.roster.issue");
        assertThat(row(forB, unlinked).allowedWorkIds()).containsExactly("vm.roster.issue");
    }

    @Test
    void existingAllQueryKeepsUnlinkedTargetsForSystemAndFullActors() throws Exception {
        long system = user(UserRole.SYS_ADMIN);
        long full = user(UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbc, full, institutionA, UserRole.ORG_ADMIN);
        for (long actor : List.of(system, full)) {
            var reference = row(list(actor, institutionA, true), unlinked);
            assertThat(reference.metadataVisible()).isTrue(); assertThat(reference.memberCount()).isEqualTo(1);
            assertThat(reference.allowedWorkIds()).contains("workspace.support.read", "vm.roster.issue", "llm.roster.issue");
            assertThat(list(actor, SeedFixtures.UNKNOWN_ID, true)).isEmpty();
            assertThat(list(actor, SeedFixtures.UNKNOWN_ID, false)).isEmpty();
        }
    }

    @Test
    void systemAnnouncementDiscoveryUsesSelectedInstitutionQualification() {
        long system = user(UserRole.SYS_ADMIN);
        var actor = actors.authenticate(publicId("users", system), users.findById(system).orElseThrow().getTokenVersion()).orElseThrow();
        assertThat(queries.announcementWorkspaces(actor, publicId("orgs", institutionB))).containsExactly(linkedB);
        assertThat(queries.announcementWorkspaces(actor, SeedFixtures.UNKNOWN_ID)).isEmpty();
    }

    private List<AdminWorkspaceOptionResponse> list(long actor, long institution, boolean all) throws Exception { return list(actor, publicId("orgs", institution), all); }
    private List<AdminWorkspaceOptionResponse> list(long actor, UUID institution, boolean all) throws Exception {
        String body = mvc.perform(get("/api/v1/admin/workspaces").param("orgId", institution.toString()).param("all", String.valueOf(all))
                .header("Authorization", bearer(actor))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Arrays.asList(json.readValue(body, AdminWorkspaceOptionResponse[].class));
    }
    private AdminWorkspaceOptionResponse row(List<AdminWorkspaceOptionResponse> rows, long workspace) {
        return rows.stream().filter(row -> row.id().equals(publicId("workspaces", workspace))).findFirst().orElseThrow();
    }
    private String bearer(long user) { return "Bearer " + jwt.createAccessToken(users.findById(user).orElseThrow()); }
    private long institution() { return jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "Reference " + UUID.randomUUID()); }
    private long workspace() {
        long workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id", Long.class, "Reference " + UUID.randomUUID());
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, owner);
        long disabled = user(UserRole.USER); jdbc.update("update users set status='DISABLED' where id=?", disabled);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspace, disabled);
        return workspace;
    }
    private long user(UserRole role) { return jdbc.queryForObject("insert into users(email,password_hash,name,role,status,email_verified_at) values(?,?,?,?::user_role,'ACTIVE',now()) returning id",
            Long.class, "reference-" + UUID.randomUUID() + "@example.invalid", "{synthetic-no-login}", "Synthetic reference", role.name()); }
    private UUID publicId(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
    private InstitutionWorkspaceScope listed(long workspace) { return InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", workspace))); }
    private void grant(long account, long institution, Set<InstitutionWork> work, Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes) {
        var assignment = InstitutionAssignment.selected(publicId("users", account), publicId("orgs", institution), work, scopes, 0);
        var encoded = new InstitutionAssignmentCodec().encode(assignment);
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision) values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))",
                account, institution, assignment.displayRole().name(), encoded.mode(), encoded.payload());
    }
}
