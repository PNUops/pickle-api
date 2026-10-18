package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPolicyService;
import kr.ac.pusan.pickle.networkpolicy.dto.UpdateVmNetworkPolicyRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionWorkspaceProjectionTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.vm-firewall.enabled", () -> "true");
        registry.add("pickle.vm-firewall.barrier-group", () -> "pickle-guard");
        registry.add("pickle.vm-firewall.ssh-gateway-source-ips", () -> "198.51.100.10");
        registry.add("pickle.vm-firewall.terminal-source-ips", () -> "198.51.100.20");
        registry.add("pickle.vm-firewall.proxy-source-ips", () -> "198.51.100.30");
        registry.add("pickle.vm-firewall.relay-source-ips", () -> "198.51.100.40");
        registry.add("jobrunr.background-job-server.enabled", () -> "false");
    }
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private DataSource dataSource;
    @Autowired private InstitutionActorProvider actors;
    @Autowired private InstitutionResourceQueries resourceQueries;
    @Autowired private VmNetworkPolicyService vmPolicies;
    private long institutionA;
    private long institutionB;
    private long workspace;
    private long otherWorkspace;
    private long owner;
    private long lastActor;

    @BeforeEach
    void setUp() {
        institutionA = institution(); institutionB = institution();
        workspace = workspace(); otherWorkspace = workspace(); owner = user(UserRole.USER);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, owner);
        vm(institutionA, workspace); vm(institutionB, workspace);
    }

    @Test
    void workspaceMetadataAloneDoesNotExposeTheWholeCrossInstitutionVmCount() throws Exception {
        String token = selected(Set.of(InstitutionWork.WORKSPACE_SUPPORT_READ), Map.of(
                InstitutionWorkGroup.WORKSPACE, listed(workspace)));
        mvc.perform(get(path()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metadataVisible").value(true))
                .andExpect(jsonPath("$.memberCount").value(1)).andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.vmCount").isEmpty());
    }

    @Test
    void directInstitutionVmMetadataDoesNotClaimAWholeWorkspaceTotalForAnyResourceDistribution() throws Exception {
        String token = selected(Set.of(InstitutionWork.WORKSPACE_SUPPORT_READ, InstitutionWork.VM_METADATA_READ), Map.of(
                InstitutionWorkGroup.WORKSPACE, listed(workspace), InstitutionWorkGroup.VM, listed(workspace)));
        expectCount(token, null);
        jdbc.update("update vms set org_id=? where workspace_id=?", institutionA, workspace);
        expectCount(token, null);
        jdbc.update("update vms set org_id=? where workspace_id=?", institutionB, workspace);
        expectCount(token, null);
    }

    @Test
    void completeGlobalVmMetadataSourceForTheSameListedWorkspaceRevealsTheWholeCount() throws Exception {
        expectCount(selected(Set.of(InstitutionWork.WORKSPACE_SUPPORT_READ, InstitutionWork.VM_METADATA_SUPPORT_READ), Map.of(
                InstitutionWorkGroup.WORKSPACE, listed(workspace), InstitutionWorkGroup.VM, listed(workspace))), 2);
    }

    @Test
    void completeGlobalVmMetadataAllScopeRevealsTheWholeCount() throws Exception {
        expectCount(selected(Set.of(InstitutionWork.WORKSPACE_SUPPORT_READ, InstitutionWork.VM_METADATA_SUPPORT_READ), Map.of(
                InstitutionWorkGroup.WORKSPACE, listed(workspace), InstitutionWorkGroup.VM, InstitutionWorkspaceScope.all())), 2);
    }

    @Test
    void anotherOrEmptyVmWorkspaceScopeCannotBorrowTheWorkspaceReadScope() throws Exception {
        expectCount(selected(Set.of(InstitutionWork.WORKSPACE_SUPPORT_READ, InstitutionWork.VM_METADATA_SUPPORT_READ), Map.of(
                InstitutionWorkGroup.WORKSPACE, listed(workspace), InstitutionWorkGroup.VM, listed(otherWorkspace))), null);
        expectCount(selected(Set.of(InstitutionWork.WORKSPACE_SUPPORT_READ, InstitutionWork.VM_METADATA_SUPPORT_READ), Map.of(
                InstitutionWorkGroup.WORKSPACE, listed(workspace), InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of()))), null);
    }

    @Test
    void independentCompleteGlobalVmSourceMayComeFromAnotherGrantingInstitution() throws Exception {
        String token = selected(Set.of(InstitutionWork.WORKSPACE_SUPPORT_READ), Map.of(InstitutionWorkGroup.WORKSPACE, listed(workspace)));
        insertGrant(lastActor, institutionB, InstitutionAssignment.selected(publicId("users", lastActor), publicId("orgs", institutionB),
                Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ), Map.of(InstitutionWorkGroup.VM, listed(workspace)), 0));
        expectCount(token, 2);
    }

    @Test
    void infoOnlyAndInvalidContextDoNotFabricateWorkspaceCounts() throws Exception {
        String token = selected(Set.of(InstitutionWork.WORKSPACE_INFO_UPDATE), Map.of(InstitutionWorkGroup.WORKSPACE, listed(workspace)));
        mvc.perform(get(path()).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metadataVisible").value(false)).andExpect(jsonPath("$.memberCount").isEmpty())
                .andExpect(jsonPath("$.vmCount").isEmpty()).andExpect(jsonPath("$.members").isEmpty());
        long unconvertedDisplay = user(UserRole.ORG_VIEWER);
        var invalid = actors.authenticate(publicId("users", unconvertedDisplay), 0).orElseThrow();
        assertThat(resourceQueries.canReadWorkspaceVmTotal(invalid, publicId("workspaces", workspace))).isFalse();
        mvc.perform(get(path()).header("Authorization", "Bearer " + jwt.createAccessToken(users.findById(unconvertedDisplay).orElseThrow())))
                .andExpect(status().isNotFound());
    }

    @Test
    void vmMetadataAloneDoesNotAdmitTheWorkspaceDetail() throws Exception {
        String token = selected(Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ), Map.of(InstitutionWorkGroup.VM, listed(workspace)));
        mvc.perform(get(path()).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"ORG_ADMIN", "ORG_MANAGER", "ORG_VIEWER"})
    void fullAndFiniteLegacyPresetsRetainTheirWholeWorkspaceCount(UserRole role) throws Exception {
        long actor = user(role);
        SeedFixtures.grantOrgRole(jdbc, actor, institutionA, role);
        expectCount(jwt.createAccessToken(users.findById(actor).orElseThrow()), 2);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"SYS_ADMIN", "SYS_MANAGER", "SYS_VIEWER"})
    void explicitSystemReadersRetainTheWholeWorkspaceCount(UserRole role) throws Exception {
        long actor = user(role);
        expectCount(jwt.createAccessToken(users.findById(actor).orElseThrow()), 2);
    }

    @Test
    void administratorVmPolicyCommandStartsItsFreshGuardInsideTheMutationTransaction() throws Exception {
        long target = prepareVmPolicy();
        String token = selected(Set.of(InstitutionWork.VM_NETWORK_POLICY_WRITE), Map.of(InstitutionWorkGroup.VM, listed(workspace)));
        updatePolicy(target, token).andExpect(status().isAccepted()).andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void existingSessionVmLockRejectsTheAdministratorWithoutMutationAndReleaseAllowsTheSameCas() throws Exception {
        long target = prepareVmPolicy();
        String token = selected(Set.of(InstitutionWork.VM_NETWORK_POLICY_WRITE), Map.of(InstitutionWorkGroup.VM, listed(workspace)));
        try (var connection = dataSource.getConnection(); var lock = connection.prepareStatement("select pg_advisory_lock(?)");
                var unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            long key = 0x564d465700000000L ^ target;
            lock.setLong(1, key); lock.execute();
            try {
                updatePolicy(target, token).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("VM_NETWORK_POLICY_UNAVAILABLE"));
                assertThat(jdbc.queryForObject("select revision from vm_network_policies where vm_id=?", Long.class, target)).isZero();
            } finally { unlock.setLong(1, key); unlock.execute(); }
        }
        updatePolicy(target, token).andExpect(status().isAccepted()).andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void administratorVmPolicyReloadRejectsAStaleSameRoleGrantBeforeWriting() {
        long target = prepareVmPolicy();
        selected(Set.of(InstitutionWork.VM_NETWORK_POLICY_WRITE), Map.of(InstitutionWorkGroup.VM, listed(workspace)));
        var original = actors.authenticate(publicId("users", lastActor), 0).orElseThrow();
        var changed = new InstitutionAssignmentCodec().encode(InstitutionAssignment.selected(original.publicId(), publicId("orgs", institutionA),
                Set.of(InstitutionWork.VM_PERIOD), Map.of(InstitutionWorkGroup.VM, listed(workspace)), 0));
        jdbc.update("update user_org_roles set permission_mode=?,permission_payload=?::jsonb,permission_revision=nextval('institution_permission_revision_seq') where user_id=?",
                changed.mode(), changed.payload(), lastActor);
        assertThatThrownBy(() -> vmPolicies.adminUpdate(original, publicId("vms", target), new UpdateVmNetworkPolicyRequest(0L, java.util.List.of()), "127.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.getStatus().value()).isEqualTo(404));
        assertThat(jdbc.queryForObject("select revision from vm_network_policies where vm_id=?", Long.class, target)).isZero();
    }

    private long prepareVmPolicy() {
        long target = jdbc.queryForObject("select id from vms where workspace_id=? and org_id=?", Long.class, workspace, institutionA);
        jdbc.update("""
                update nodes set labels=coalesce(labels,'{}'::jsonb)
                    || '{"vm_firewall_policy":{"schema_version":1},"vm_nic_requirements":{"schema_version":1,"mtu":1370,"firewall":true}}'::jsonb
                 where id=(select node_id from vms where id=?)
                """, target);
        jdbc.update("insert into vm_network_policies(vm_id,revision,desired_generation,desired_hash,apply_state) values(?,0,1,?,'PENDING')",
                target, "0".repeat(64));
        return target;
    }

    private org.springframework.test.web.servlet.ResultActions updatePolicy(long target, String token) throws Exception {
        return mvc.perform(put("/api/v1/admin/vms/" + publicId("vms", target) + "/network-policy")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"rules\":[]}"));
    }

    private void expectCount(String token, Integer count) throws Exception {
        var response = mvc.perform(get(path()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        if (count == null) { response.andExpect(jsonPath("$.vmCount").isEmpty()); }
        else { response.andExpect(jsonPath("$.vmCount").value(count)); }
    }

    private String selected(Set<InstitutionWork> work, Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes) {
        UserRole role = work.stream().anyMatch(InstitutionWork::write) ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER;
        long actor = user(role); lastActor = actor;
        InstitutionAssignment assignment = InstitutionAssignment.selected(publicId("users", actor),
                publicId("orgs", institutionA), work, scopes, 0);
        insertGrant(actor, institutionA, assignment);
        return jwt.createAccessToken(users.findById(actor).orElseThrow());
    }

    private void insertGrant(long actor, long institution, InstitutionAssignment assignment) {
        var encoded = new InstitutionAssignmentCodec().encode(assignment);
        jdbc.update("""
                insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision)
                values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))
                """, actor, institution, assignment.displayRole().name(), encoded.mode(), encoded.payload());
    }

    private InstitutionWorkspaceScope listed(long id) {
        return InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", id)));
    }

    private long institution() {
        return jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "Count institution " + UUID.randomUUID());
    }

    private long workspace() {
        return jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id", Long.class, "Count workspace " + UUID.randomUUID());
    }

    private long user(UserRole role) {
        return jdbc.queryForObject("insert into users(email,name,role,status) values(?,'Synthetic count actor',?::user_role,'ACTIVE') returning id",
                Long.class, UUID.randomUUID() + "@example.invalid", role.name());
    }

    private void vm(long institution, long targetWorkspace) {
        long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        long request = RequestFixtures.insertVmRequest(jdbc, targetWorkspace, institution, owner, "Synthetic count", image);
        String name = "projection-" + UUID.randomUUID();
        jdbc.update("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                values(?,?,?,?,?,?,?,1,1024,10,'STOPPED')
                """, node, targetWorkspace, institution, request, name, name, image);
    }

    private UUID publicId(String table, long id) {
        return jdbc.queryForObject("select public_id from " + table + " where id=?", UUID.class, id);
    }

    private String path() { return "/api/v1/admin/workspaces/" + publicId("workspaces", workspace); }
}
