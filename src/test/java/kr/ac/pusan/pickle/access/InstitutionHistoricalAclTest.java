package kr.ac.pusan.pickle.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import kr.ac.pusan.pickle.support.RequestFixtures;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionHistoricalAclTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private TransactionTemplate transactions;
    private long sourceInstitution;
    private long targetInstitution;
    private long workspace;
    private long vm;
    private User grantee;
    private UUID grant;

    @BeforeEach
    void retainedTarget() {
        sourceInstitution = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class,
                "ACL source " + UUID.randomUUID());
        targetInstitution = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class,
                "ACL target " + UUID.randomUUID());
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id", Long.class,
                "Retained ACL " + UUID.randomUUID());
        grantee = account();
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspace, grantee.getId());
        vm = retainedVm(workspace);
        grant = jdbc.queryForObject("""
                insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role)
                values('VM',?,'USER',?,'EDITOR') returning public_id
                """, UUID.class, vm, grantee.getId());
    }

    @Test
    void historicalReadAndAdvertisedManagementAllowRetainedGrantRemoval() throws Exception {
        String token = actor(Set.of(InstitutionWork.VM_ACL_SUPPORT_READ, InstitutionWork.VM_ACL_SUPPORT_MANAGE));
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", vm)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedWorkIds").value(hasItem(InstitutionWork.VM_ACL_SUPPORT_MANAGE.id())));
        var listed = mvc.perform(get(path(vm)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        boolean advertised = json.readTree(listed.getResponse().getContentAsString()).get("canManageAccess").asBoolean();
        var removed = mvc.perform(delete(path(vm) + "/" + grant).header("Authorization", "Bearer " + token)).andReturn();
        assertAll("historical management matches the advertised action",
                () -> assertThat(advertised).isTrue(),
                () -> assertThat(removed.getResponse().getStatus()).isEqualTo(204),
                () -> assertThat(grantCount()).isZero());
    }

    @Test
    void anActiveNonMemberCannotReceiveANewGrantInTheClosedWorkspace() throws Exception {
        String token = actor(Set.of(InstitutionWork.VM_ACL_SUPPORT_MANAGE));
        User candidate = account();
        mvc.perform(post(path(vm)).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("granteeType", "USER", "userId", candidate.getPublicId(), "role", "VIEWER"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("userId"));
        assertThat(jdbc.queryForObject("select count(*) from resource_access_grants where resource_type='VM' and resource_id=? and user_id=?",
                Long.class, vm, candidate.getId())).isZero();
        assertThat(grantCount()).isEqualTo(1);
    }

    @Test
    void aHistoricalReadSourceCannotRemoveTheGrant() throws Exception {
        String token = actor(Set.of(InstitutionWork.VM_ACL_SUPPORT_READ));
        mvc.perform(get(path(vm)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canManageAccess").value(false));
        mvc.perform(delete(path(vm) + "/" + grant).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(grantCount()).isEqualTo(1);
    }

    @Test
    void anotherClosedWorkspaceCannotBorrowTheListedScope() throws Exception {
        String token = actor(Set.of(InstitutionWork.VM_ACL_SUPPORT_READ, InstitutionWork.VM_ACL_SUPPORT_MANAGE));
        long outside = jdbc.queryForObject("insert into workspaces(kind,name,deleted_at) values('PROJECT',?,now()) returning id",
                Long.class, "Other retained ACL " + UUID.randomUUID());
        long foreignVm = retainedVm(outside);
        UUID foreignGrant = jdbc.queryForObject("""
                insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role)
                values('VM',?,'USER',?,'EDITOR') returning public_id
                """, UUID.class, foreignVm, grantee.getId());
        mvc.perform(get(path(foreignVm)).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(delete(path(foreignVm) + "/" + foreignGrant).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from resource_access_grants where public_id=?", Long.class, foreignGrant)).isEqualTo(1);
    }

    private String actor(Set<InstitutionWork> work) {
        User user = account();
        UserRole role = work.stream().anyMatch(InstitutionWork::write) ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER;
        SeedFixtures.grantOrgRole(jdbc, user.getId(), sourceInstitution, role);
        long revision = jdbc.queryForObject("select permission_revision from user_org_roles where user_id=? and org_id=?",
                Long.class, user.getId(), sourceInstitution);
        var assignment = InstitutionAssignment.selected(user.getPublicId(), publicId("orgs", sourceInstitution), work,
                Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", workspace)))), 0);
        transactions.executeWithoutResult(status -> new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec()).save(assignment, revision));
        jdbc.update("delete from workspace_members where workspace_id=?", workspace);
        jdbc.update("update workspaces set deleted_at=now() where id=?", workspace);
        return jwt.createAccessToken(users.findById(user.getId()).orElseThrow());
    }

    private User account() {
        User user = new User("historical-acl-" + UUID.randomUUID() + "@example.test", "{noop}unused", "합성 ACL 계정");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private long retainedVm(long inWorkspace) {
        long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        long request = RequestFixtures.insertVmRequest(jdbc, inWorkspace, targetInstitution, grantee.getId(), "Retained ACL", image);
        String name = "historical-acl-" + UUID.randomUUID();
        return jdbc.queryForObject("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status,deleted_at)
                values((select min(id) from nodes),?,?,?,?,?,?,1,1024,20,'DELETED',now()) returning id
                """, Long.class, inWorkspace, targetInstitution, request, name, name, image);
    }

    private String path(long target) { return "/api/v1/admin/resource-access/VM/" + publicId("vms", target); }
    private UUID publicId(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
    private long grantCount() { return jdbc.queryForObject("select count(*) from resource_access_grants where public_id=?", Long.class, grant); }
}
