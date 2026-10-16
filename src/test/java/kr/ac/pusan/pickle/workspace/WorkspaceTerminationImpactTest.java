package kr.ac.pusan.pickle.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class WorkspaceTerminationImpactTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtService tokens;
    @Autowired ObjectMapper json;
    @Autowired List<ResourceTypeAdapter> adapters;
    @Autowired ResourceAccessGrantRepository grants;
    private User owner;
    private long workspace;
    private long org;

    @BeforeEach
    void setup() {
        owner = user(UserRole.USER);
        org = SeedFixtures.seedOrgId(jdbc);
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id",
                Long.class, "종료 영향 " + UUID.randomUUID());
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, owner.getId());
    }

    @Test
    void existingOwnerAndSupportWriterBoundariesApplyWithoutInstitutionFiltering() throws Exception {
        request(org, "SUBMITTED");
        long invitation = invitation("roles");
        for (UserRole role : UserRole.values()) {
            User actor = user(role);
            boolean writer = List.of(UserRole.ORG_MANAGER, UserRole.ORG_ADMIN, UserRole.SYS_ADMIN).contains(role);
            mvc.perform(get(adminPath()).header("Authorization", bearer(actor)))
                    .andExpect(writer ? status().isOk() : status().isForbidden());
            mvc.perform(get(adminInvitationPath(invitation)).header("Authorization", bearer(actor)))
                    .andExpect(writer ? status().isOk() : status().isForbidden());
            jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, actor.getId());
            mvc.perform(get(path()).header("Authorization", bearer(actor))).andExpect(status().isOk());
            mvc.perform(get(invitationPath(invitation)).header("Authorization", bearer(actor))).andExpect(status().isOk());
        }
        User member = user(UserRole.USER);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspace, member.getId());
        mvc.perform(get(path()).header("Authorization", bearer(member))).andExpect(status().isForbidden());
        mvc.perform(get(invitationPath(invitation)).header("Authorization", bearer(member))).andExpect(status().isForbidden());
        mvc.perform(get(path()).header("Authorization", bearer(user(UserRole.USER)))).andExpect(status().isNotFound());
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
    }

    @Test
    void wholeWorkspaceCountsRequestsAndRecipientsOnceAcrossInstitutionsAndPages() throws Exception {
        long other = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "다른 기관 " + UUID.randomUUID());
        for (int i = 0; i < 25; i++) request(i % 2 == 0 ? org : other, "SUBMITTED");
        long approved = request(other, "APPROVED");
        recipient(approved, null, "QUEUED");
        recipient(approved, invitation("held"), "PENDING_JOIN");
        recipient(approved, null, "FAILED");
        JsonNode first = read(path(), owner);
        assertThat(first.at("/summary/submittedRequests").asLong()).isEqualTo(25);
        assertThat(first.at("/summary/closingRecipients").asLong()).isEqualTo(2);
        assertThat(first.at("/summary/failedRecipients").asLong()).isEqualTo(1);
        assertThat(first.at("/summary/approvedRecipients").asLong()).isEqualTo(2);
        assertThat(first.at("/targets/totalElements").asLong()).isEqualTo(28);
        assertThat(first.at("/targets/content").size()).isEqualTo(20);
        JsonNode later = read(path() + "?page=1", owner);
        assertThat(later.at("/targets/content").size()).isEqualTo(8);
        assertThat(later.get("fingerprint").asString()).isEqualTo(first.get("fingerprint").asString());
        assertThat(later.at("/targets/content/7/willClose").asBoolean()).isFalse();
        assertThat(later.at("/targets/content/7/orgId").asString()).isEqualTo(pub("orgs", other).toString());
        User supporter = user(UserRole.ORG_MANAGER);
        JsonNode support = read(adminPath() + "?orgId=" + SeedFixtures.UNKNOWN_ID, supporter);
        assertThat(support.at("/summary/submittedRequests").asLong()).isEqualTo(25);
        assertThat(support.at("/targets/totalElements").asLong()).isEqualTo(28);
    }

    @Test
    void invitationUsesExactForeignKeyAcrossRequestsAndKeepsFailuresSeparate() throws Exception {
        long invitation = invitation("exact");
        long other = invitation("other");
        long submitted = request(org, "SUBMITTED");
        long approved = request(org, "APPROVED");
        recipient(submitted, invitation, "PENDING_JOIN");
        recipient(approved, invitation, "PENDING_JOIN");
        recipient(approved, other, "PENDING_JOIN");
        long failedRequest = request(org, "APPROVED");
        recipient(failedRequest, invitation, "FAILED");
        JsonNode result = read(invitationPath(invitation), owner);
        assertThat(result.at("/summary/submittedRequests").asLong()).isZero();
        assertThat(result.at("/summary/closingRecipients").asLong()).isEqualTo(2);
        assertThat(result.at("/summary/approvedRecipients").asLong()).isEqualTo(1);
        assertThat(result.at("/summary/undecidedRecipients").asLong()).isEqualTo(1);
        assertThat(result.at("/summary/failedRecipients").asLong()).isEqualTo(1);
        assertThat(result.at("/targets/totalElements").asLong()).isEqualTo(3);
        for (JsonNode target : result.at("/targets/content")) {
            assertThat(target.get("invitationId").asString()).isEqualTo(pub("workspace_invitations", invitation).toString());
        }
        jdbc.update("update workspace_invitations set status='CANCELED',canceled_at=now(),canceled_by=? where id=?", owner.getId(), invitation);
        mvc.perform(get(invitationPath(invitation)).header("Authorization", bearer(owner))).andExpect(status().isNotFound());
    }

    @Test
    void fingerprintTracksIdentitiesAndStatesButNotObservationTimeOrPage() throws Exception {
        long request = request(org, "SUBMITTED");
        JsonNode before = read(path(), owner);
        JsonNode repeated = read(path(), owner);
        assertThat(repeated.get("fingerprint").asString()).isEqualTo(before.get("fingerprint").asString());
        assertThat(repeated.get("observedAt").asString()).isNotEqualTo(before.get("observedAt").asString());
        jdbc.update("update requests set status='CANCELED' where id=?", request);
        request(org, "SUBMITTED");
        JsonNode replaced = read(path(), owner);
        assertThat(replaced.at("/summary/submittedRequests").asLong()).isEqualTo(before.at("/summary/submittedRequests").asLong());
        assertThat(replaced.get("fingerprint").asString()).isNotEqualTo(before.get("fingerprint").asString());
        long approved = request(org, "APPROVED");
        long recipient = recipient(approved, null, "QUEUED");
        String queued = read(path(), owner).get("fingerprint").asString();
        jdbc.update("update request_recipients set status='PENDING_JOIN' where id=?", recipient);
        assertThat(read(path(), owner).get("fingerprint").asString()).isNotEqualTo(queued);
        String pending = read(path(), owner).get("fingerprint").asString();
        jdbc.update("update request_recipients set resource_id=987654321 where id=?", recipient);
        assertThat(read(path(), owner).get("fingerprint").asString()).isNotEqualTo(pending);
        String renamed = read(path(), owner).get("fingerprint").asString();
        jdbc.update("update workspaces set name='이름만 수정' where id=?", workspace);
        jdbc.update("update requests set display_name='표시 이름만 수정' where workspace_id=?", workspace);
        assertThat(read(path(), owner).get("fingerprint").asString()).isEqualTo(renamed);
        User support = user(UserRole.ORG_MANAGER);
        assertThat(read(adminPath(), support).get("fingerprint").asString()).isNotEqualTo(read(path(), owner).get("fingerprint").asString());
    }

    @Test
    void emptyAndBlockedObservationsDoNotWriteAnyDomainOrOperationalState() throws Exception {
        JsonNode empty = read(path(), owner);
        assertThat(empty.get("executable").asBoolean()).isTrue();
        assertThat(empty.at("/targets/totalElements").asLong()).isZero();
        long request = request(org, "SUBMITTED");
        long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        jdbc.update("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                values(?,?,?,?,?,?,?,1,1024,10,'CREATING')
                """, node, workspace, org, request, "impact", "impact-" + UUID.randomUUID(), image);
        List<String> before = databaseState();
        JsonNode blocked = read(path(), owner);
        assertThat(blocked.get("executable").asBoolean()).isFalse();
        assertThat(blocked.get("blockedReason").asString()).isEqualTo("REMAINING_RESOURCES");
        assertThat(blocked.at("/resourceBlockers/0/type").asString()).isEqualTo("VM");
        assertThat(blocked.at("/resourceBlockers/0/count").asLong()).isEqualTo(1);
        assertThat(databaseState()).isEqualTo(before);
        String creating = blocked.get("fingerprint").asString();
        jdbc.update("update vms set status='ERROR' where request_id=?", request);
        JsonNode failedVm = read(path(), owner);
        assertThat(failedVm.at("/resourceBlockers/0/count").asLong()).isEqualTo(1);
        assertThat(failedVm.get("fingerprint").asString()).isNotEqualTo(creating);
        jdbc.update("update vms set status='DELETED' where request_id=?", request);
        assertThat(read(path(), owner).get("executable").asBoolean()).isTrue();
    }

    @Test
    void systemSubjectsRemainInTheImpactWhileSupportLinksRespectExistingMasking() throws Exception {
        User subject = user(UserRole.SYS_ADMIN);
        long request = request(org, "APPROVED");
        jdbc.update("insert into request_recipients(public_id,request_id,user_id,status) values(?,?,?,'QUEUED')",
                UUID.randomUUID(), request, subject.getId());
        JsonNode institution = read(adminPath(), user(UserRole.ORG_ADMIN));
        assertThat(institution.at("/summary/closingRecipients").asLong()).isEqualTo(1);
        assertThat(institution.at("/targets/content/0/name").asString()).isEqualTo(subject.getName());
        assertThat(institution.at("/targets/content/0/userReadable").asBoolean()).isFalse();
        assertThat(read(adminPath(), user(UserRole.SYS_ADMIN)).at("/targets/content/0/userReadable").asBoolean()).isTrue();
        jdbc.update("update workspaces set kind='PERSONAL' where id=?", workspace);
        JsonNode personal = read(path(), owner);
        assertThat(personal.get("executable").asBoolean()).isFalse();
        assertThat(personal.get("blockedReason").asString()).isEqualTo("PERSONAL");
    }

    @Test
    void invalidPagesAndDirectTargetsCannotUseAnotherWorkspaceInvitation() throws Exception {
        mvc.perform(get(path() + "?page=-1").header("Authorization", bearer(owner))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(path() + "?size=101").header("Authorization", bearer(owner))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(path() + "?resourcePage=-1").header("Authorization", bearer(owner))).andExpect(status().isUnprocessableEntity());
        long invitation = invitation("foreign-workspace");
        long other = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT','다른 공간') returning id", Long.class);
        jdbc.update("update workspace_invitations set workspace_id=? where id=?", other, invitation);
        mvc.perform(get(invitationPath(invitation)).header("Authorization", bearer(owner))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workspaces/" + SeedFixtures.UNKNOWN_ID + "/termination-impact").header("Authorization", bearer(owner))).andExpect(status().isNotFound());
    }

    @Test
    void blockingResourcePagesMatchAllExistingDeletionPredicatesAndKeepExactPublicIdentities() throws Exception {
        long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        long vmRequest = request(org, "APPROVED");
        jdbc.update("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                values(?,?,?,?,?,?,?,1,1024,10,'DELETING')
                """, node, workspace, org, vmRequest, "남은 VM", "remaining-" + UUID.randomUUID(), image);
        long keyRequest = RequestFixtures.insertLlmKeyRequest(jdbc, workspace, org, owner.getId(), "남은 키");
        for (int i = 0; i < 23; i++) {
            jdbc.update("insert into llm_api_keys(workspace_id,org_id,request_id,name,status,created_by) values(?,?,?,?,'EXPIRED',?)",
                    workspace, org, keyRequest, "기한이 지난 키 " + i, owner.getId());
        }
        long domain = jdbc.queryForObject("""
                insert into domains(workspace_id,kind,fqdn,status,dns_status,renew_due_at,released_at)
                values(?,'EXTERNAL',?,'ACTIVE','NONE',now()+interval '30 days',now()) returning id
                """, Long.class, workspace, UUID.randomUUID() + ".example.test");
        long gpuRequest = request(org, "APPROVED");
        long gpu = jdbc.queryForObject("insert into gpu_allocations(request_id,workspace_id,org_id,name,granted_lease_hours) values(?,?,?,'대기 GPU',1) returning id",
                Long.class, gpuRequest, workspace, org);
        JsonNode first = read(path(), owner);
        assertThat(first.at("/blockingResources/totalElements").asLong()).isEqualTo(26);
        assertThat(first.at("/blockingResources/content").size()).isEqualTo(20);
        for (ResourceTypeAdapter adapter : adapters) {
            for (JsonNode blocker : first.get("resourceBlockers")) {
                if (adapter.type().name().equals(blocker.get("type").asString())) {
                    assertThat(blocker.get("count").asLong()).isEqualTo(adapter.countLiveInWorkspace(workspace));
                }
            }
        }
        JsonNode second = read(path() + "?resourcePage=1", owner);
        assertThat(second.at("/blockingResources/content").size()).isEqualTo(6);
        assertThat(second.get("fingerprint").asString()).isEqualTo(first.get("fingerprint").asString());
        assertThat(second.at("/blockingResources/content/4/id").asString()).isEqualTo(pub("domains", domain).toString());
        assertThat(second.at("/blockingResources/content/4/status").asString()).isEqualTo("ACTIVE");
        assertThat(second.at("/blockingResources/content/4/orgId").isNull()).isTrue();
        assertThat(second.at("/blockingResources/content/5/id").asString()).isEqualTo(pub("gpu_allocations", gpu).toString());
        assertThat(second.at("/blockingResources/content/5/orgId").isNull()).isTrue();
        assertThat(second.at("/blockingResources/content/5/resourceReadable").asBoolean()).isFalse();
        User support = user(UserRole.ORG_MANAGER);
        JsonNode supported = read(adminPath() + "?resourcePage=1", support);
        assertThat(supported.at("/blockingResources/content/4/resourceReadable").asBoolean()).isFalse();
        assertThat(supported.at("/blockingResources/content/5/resourceReadable").asBoolean()).isTrue();
        assertThat(supported.at("/blockingResources/content/5/orgId").asString()).isEqualTo(pub("orgs", org).toString());
        mvc.perform(get("/api/v1/admin/domains/" + pub("domains", domain)).header("Authorization", bearer(support))).andExpect(status().isNotFound());
        long invitation = invitation("resource-unrelated");
        assertThat(read(invitationPath(invitation), owner).at("/blockingResources/totalElements").asLong()).isZero();
    }

    @Test
    void ownerResourceProjectionReusesExistingRestrictionAndBindsWholeWorkspaceGrants() throws Exception {
        long first = vm("private-slug-" + UUID.randomUUID());
        long second = vm("other-private-slug-" + UUID.randomUUID());
        UUID firstId = pub("vms", first);
        JsonNode restricted = read(path() + "?size=1", owner);
        assertThat(restricted.at("/blockingResources/content/0/name").asString()).isEqualTo("VM #" + firstId);
        assertThat(restricted.at("/blockingResources/content/0/orgId").isNull()).isTrue();
        assertThat(restricted.at("/blockingResources/content/0/orgName").isNull()).isTrue();
        assertThat(restricted.at("/blockingResources/content/0/resourceReadable").asBoolean()).isFalse();
        mvc.perform(get("/api/v1/vms/" + firstId).header("Authorization", bearer(owner))).andExpect(status().isForbidden());
        jdbc.update("insert into vm_settings(vm_id,key,value,updated_by) values(?,'display_name','\"공개 표시 이름\"'::jsonb,?)", first, owner.getId());
        JsonNode named = read(path() + "?size=1", owner);
        JsonNode listed = read("/api/v1/vms?workspaceId=" + pub("workspaces", workspace), owner);
        assertThat(named.at("/blockingResources/content/0/name").asString()).isEqualTo("공개 표시 이름");
        for (JsonNode listedVm : listed.get("content")) {
            if (listedVm.get("id").asString().equals(firstId.toString())) {
                assertThat(named.at("/blockingResources/content/0/name").asString()).isEqualTo(listedVm.get("name").asString());
            }
        }
        assertThat(named.get("fingerprint").asString()).isEqualTo(restricted.get("fingerprint").asString());
        ResourceAccessGrant direct = grants.save(ResourceAccessGrant.forUser(ResourceType.VM, first, owner.getId(), ResourceRole.VIEWER));
        JsonNode open = read(path() + "?size=1", owner);
        assertThat(open.at("/blockingResources/content/0/resourceReadable").asBoolean()).isTrue();
        assertThat(open.at("/blockingResources/content/0/orgId").asString()).isEqualTo(pub("orgs", org).toString());
        assertThat(open.get("fingerprint").asString()).isNotEqualTo(restricted.get("fingerprint").asString());
        grants.deleteById(direct.getId());
        assertThat(read(path() + "?size=1", owner).get("fingerprint").asString()).isEqualTo(restricted.get("fingerprint").asString());
        ResourceAccessGrant workspaceGrant = grants.save(ResourceAccessGrant.forOwningWorkspace(ResourceType.VM, second, ResourceRole.MEMBER));
        JsonNode outsidePage = read(path() + "?size=1", owner);
        assertThat(outsidePage.at("/blockingResources/content/0/resourceReadable").asBoolean()).isFalse();
        assertThat(outsidePage.get("fingerprint").asString()).isNotEqualTo(restricted.get("fingerprint").asString());
        assertThat(read(path() + "?size=1&resourcePage=1", owner).at("/blockingResources/content/0/resourceReadable").asBoolean()).isTrue();
        grants.deleteById(workspaceGrant.getId());
        assertThat(read(path() + "?size=1", owner).get("fingerprint").asString()).isEqualTo(restricted.get("fingerprint").asString());
    }

    private long vm(String slug) {
        long request = request(org, "APPROVED");
        return jdbc.queryForObject("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                values((select min(id) from nodes),?,?,?,?,?,(select min(id) from os_images),1,1024,10,'RUNNING') returning id
                """, Long.class, workspace, org, request, slug, slug);
    }

    private List<String> databaseState() {
        return List.of("requests", "request_recipients", "workspace_invitations", "workspace_members", "audit_logs", "notifications", "auth_rate_limits", "vms", "llm_api_keys", "llm_gateway_state", "jobrunr_jobs")
                .stream().map(table -> jdbc.queryForObject("select coalesce(json_agg(t order by t.id)::text,'[]') from " + table + " t", String.class)).toList();
    }

    private long request(long institution, String state) {
        return RequestFixtures.insertVmRequestWithStatus(jdbc, workspace, institution, owner.getId(), "영향 확인", null, state);
    }

    private long recipient(long request, Long invitation, String state) {
        User subject = invitation == null ? user(UserRole.USER) : null;
        return jdbc.queryForObject("insert into request_recipients(public_id,request_id,user_id,invitation_id,status,reason) values(?,?,?,?,?,?) returning id",
                Long.class, UUID.randomUUID(), request, subject == null ? null : subject.getId(), invitation, state, "FAILED".equals(state) ? "이전 실패" : null);
    }

    private long invitation(String label) {
        return jdbc.queryForObject("insert into workspace_invitations(workspace_id,invitee_email,role,status,invited_by) values(?,?,'MEMBER','PENDING',?) returning id",
                Long.class, workspace, label + UUID.randomUUID() + "@example.test", owner.getId());
    }

    private User user(UserRole role) {
        User user = users.save(new User("impact-" + UUID.randomUUID() + "@example.test", "hash", "합성 사용자"));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user = users.save(user);
        if (role.isOrgTier()) SeedFixtures.grantOrgRole(jdbc, user.getId(), SeedFixtures.seedOrgId(jdbc), role);
        return user;
    }

    private JsonNode read(String path, User actor) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", bearer(actor))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
    private String bearer(User actor) { return "Bearer " + tokens.createAccessToken(actor); }
    private UUID pub(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
    private String path() { return "/api/v1/workspaces/" + pub("workspaces", workspace) + "/termination-impact"; }
    private String adminPath() { return "/api/v1/admin/workspaces/" + pub("workspaces", workspace) + "/termination-impact"; }
    private String invitationPath(long invitation) { return "/api/v1/workspaces/" + pub("workspaces", workspace) + "/invitations/" + pub("workspace_invitations", invitation) + "/termination-impact"; }
    private String adminInvitationPath(long invitation) { return "/api/v1/admin/workspaces/" + pub("workspaces", workspace) + "/invitations/" + pub("workspace_invitations", invitation) + "/termination-impact"; }
}
