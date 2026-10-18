package kr.ac.pusan.pickle.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.dto.AccessConfirmation;
import kr.ac.pusan.pickle.access.dto.ActorAccessImpact;
import kr.ac.pusan.pickle.access.dto.AddResourceAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.ResourceAccessChangePreviewRequest;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.institution.InstitutionActorProvider;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ResourceAccessConfirmationBoundaryTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private ResourceAccessGrantRepository grants;
    @Autowired private ResourceAccessGrantService service;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private InstitutionActorProvider actors;

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void anotherEligibleActorCannotReplayTheSameWorkspaceGradeConfirmation(ResourceType type) {
        Fixture resource = fixture(type);
        User first = manager();
        User second = manager();
        member(resource, first);
        member(resource, second);
        ActorAccessImpact firstImpact = preview(first, resource);
        assertProjectedMemberGain(firstImpact, resource);
        List<Map<String, Object>> before = snapshot(resource);
        long auditBefore = audits(resource);
        long generationBefore = generation();

        expectConfirmationRefusal(() -> addShared(second, resource, confirm(firstImpact, true)));
        assertUnchanged(resource, before, auditBefore);
        assertThat(generation()).isEqualTo(generationBefore);

        ActorAccessImpact current = preview(second, resource);
        addShared(second, resource, confirm(current, true));
        assertThat(sharedGrade(resource)).isEqualTo("MEMBER");
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void anotherResourceCannotReuseTheFirstTargetsConfirmation(ResourceType type) {
        Fixture first = fixture(type);
        Fixture second = fixture(type);
        User actor = manager();
        member(first, actor);
        member(second, actor);
        ActorAccessImpact firstImpact = preview(actor, first);
        assertProjectedMemberGain(firstImpact, first);
        List<Map<String, Object>> firstBefore = snapshot(first);
        List<Map<String, Object>> secondBefore = snapshot(second);
        long firstAudit = audits(first);
        long secondAudit = audits(second);
        long generationBefore = generation();

        expectConfirmationRefusal(() -> addShared(actor, second, confirm(firstImpact, true)));
        assertUnchanged(first, firstBefore, firstAudit);
        assertUnchanged(second, secondBefore, secondAudit);
        assertThat(generation()).isEqualTo(generationBefore);

        addShared(actor, second, confirm(preview(actor, second), true));
        assertThat(sharedGrade(second)).isEqualTo("MEMBER");
        assertUnchanged(first, firstBefore, firstAudit);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void anExactFingerprintStillRequiresAnExplicitTrueAcknowledgement(ResourceType type) {
        Fixture resource = fixture(type);
        User actor = manager();
        member(resource, actor);
        ActorAccessImpact impact = preview(actor, resource);
        assertProjectedMemberGain(impact, resource);
        List<Map<String, Object>> before = snapshot(resource);
        long auditBefore = audits(resource);
        long generationBefore = generation();

        expectConfirmationRefusal(() -> addShared(actor, resource, confirm(impact, false)));
        assertUnchanged(resource, before, auditBefore);
        assertThat(generation()).isEqualTo(generationBefore);

        addShared(actor, resource, confirm(impact, true));
        assertThat(sharedGrade(resource)).isEqualTo("MEMBER");
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void patchAndDeleteCannotReachAGrantThroughAnotherResource(ResourceType type) throws Exception {
        Fixture original = fixture(type);
        Fixture other = fixture(type);
        User actor = manager();
        String token = jwt.createAccessToken(actor);
        String path = "/api/v1/admin/resource-access/" + type + "/" + other.publicId()
                + "/" + original.ownerGrantId();
        List<Map<String, Object>> originalBefore = snapshot(original);
        List<Map<String, Object>> otherBefore = snapshot(other);
        long originalAudit = audits(original);
        long otherAudit = audits(other);
        long generationBefore = generation();

        mvc.perform(patch(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertUnchanged(original, originalBefore, originalAudit);
        assertUnchanged(other, otherBefore, otherAudit);
        mvc.perform(delete(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertUnchanged(original, originalBefore, originalAudit);
        assertUnchanged(other, otherBefore, otherAudit);
        assertThat(generation()).isEqualTo(generationBefore);
        assertThat(grants.findByPublicId(original.ownerGrantId())).isPresent();

        String correctPath = "/api/v1/admin/resource-access/" + type + "/" + original.publicId()
                + "/" + original.ownerGrantId();
        mvc.perform(patch(correctPath).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("EDITOR"));
        mvc.perform(delete(correctPath).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(grants.findByPublicId(original.ownerGrantId())).isEmpty();
        assertUnchanged(other, otherBefore, otherAudit);
    }

    private ActorAccessImpact preview(User actor, Fixture resource) {
        return service.preview(actor(actor), resource.type(), resource.publicId(),
                new ResourceAccessChangePreviewRequest(ResourceAccessChangePreviewRequest.Action.ADD,
                        null, AccessGranteeType.WORKSPACE, null, ResourceRole.MEMBER), true);
    }

    private void addShared(User actor, Fixture resource, AccessConfirmation confirmation) {
        service.add(actor(actor), resource.type(), resource.publicId(),
                new AddResourceAccessGrantRequest(AccessGranteeType.WORKSPACE, null,
                        ResourceRole.MEMBER, confirmation), "127.0.9.1", true);
    }

    private static AccessConfirmation confirm(ActorAccessImpact impact, boolean confirmed) {
        return new AccessConfirmation("합성 접근 확인 목적", impact.fingerprint(), confirmed);
    }

    private static void expectConfirmationRefusal(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class).satisfies(error -> {
            ApiException refusal = (ApiException) error;
            assertThat(refusal.getStatus().value()).isEqualTo(422);
            assertThat(refusal.getCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(refusal.getErrors()).singleElement().satisfies(field -> assertThat(field.field()).isEqualTo("confirmation"));
        });
    }

    private static void assertProjectedMemberGain(ActorAccessImpact impact, Fixture resource) {
        assertThat(impact.requiresConfirmation()).isTrue();
        assertThat(impact.increases()).singleElement().satisfies(gain -> {
            assertThat(gain.targetId()).isEqualTo(resource.publicId());
            assertThat(gain.beforeRole()).isNull();
            assertThat(gain.afterRole()).isEqualTo("MEMBER");
        });
    }

    private void assertUnchanged(Fixture resource, List<Map<String, Object>> before, long auditBefore) {
        assertThat(snapshot(resource)).isEqualTo(before);
        assertThat(audits(resource)).isEqualTo(auditBefore);
    }

    private List<Map<String, Object>> snapshot(Fixture resource) {
        return jdbc.queryForList("""
                select public_id, grantee_type::text, user_id, role::text
                  from resource_access_grants where resource_type = ?::resource_type and resource_id = ? order by id
                """, resource.type().name(), resource.id());
    }

    private long audits(Fixture resource) {
        return jdbc.queryForObject("select count(*) from audit_logs where target_id = ?", Long.class, resource.publicId().toString());
    }

    private long generation() {
        return jdbc.queryForObject("select coalesce((select generation from llm_gateway_state where id), 0)", Long.class);
    }

    private String sharedGrade(Fixture resource) {
        return jdbc.queryForObject("""
                select role::text from resource_access_grants where resource_type = ?::resource_type
                 and resource_id = ? and grantee_type = 'WORKSPACE'
                """, String.class, resource.type().name(), resource.id());
    }

    private void member(Fixture fixture, User user) {
        members.saveAndFlush(new WorkspaceMember(fixture.workspace(), user.getId(), WorkspaceMemberRole.MEMBER));
    }

    private User manager() {
        User user = user(UserRole.ORG_MANAGER);
        SeedFixtures.grantOrgRole(jdbc, user.getId(), SeedFixtures.seedOrgId(jdbc), UserRole.ORG_MANAGER);
        return user;
    }

    private User user(UserRole role) {
        User user = new User("confirmation." + UUID.randomUUID() + "@pusan.ac.kr", "{synthetic-no-login}", "합성 확인 수행자");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private AuthenticatedUser actor(User user) {
        return actors.authenticate(user.getPublicId(), user.getTokenVersion()).orElseThrow();
    }

    private Fixture fixture(ResourceType type) {
        User owner = user(UserRole.USER);
        Workspace workspace = workspaces.saveAndFlush(new Workspace(WorkspaceKind.PROJECT, "합성 확인 대상 " + UUID.randomUUID(), null));
        members.saveAndFlush(new WorkspaceMember(workspace, owner.getId(), WorkspaceMemberRole.OWNER));
        long org = SeedFixtures.seedOrgId(jdbc);
        String label = "confirmation-" + UUID.randomUUID().toString().substring(0, 8);
        String table;
        long id;
        switch (type) {
            case VM -> {
                long request = RequestFixtures.insertVmRequest(jdbc, workspace.getId(), org, owner.getId(), "합성 확인 VM", null);
                long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
                long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
                id = jdbc.queryForObject("""
                        insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                        values(?,?,?,?,?,?,?,2,2048,10,'RUNNING') returning id
                        """, Long.class, node, workspace.getId(), org, request, label, label, image);
                table = "vms";
            }
            case LLM_API_KEY -> {
                long request = RequestFixtures.insertLlmKeyRequest(jdbc, workspace.getId(), org, owner.getId(), "합성 확인 LLM");
                id = jdbc.queryForObject("""
                        insert into llm_api_keys(workspace_id,org_id,request_id,name,created_by,status)
                        values(?,?,?, ?,?,'ACTIVE') returning id
                        """, Long.class, workspace.getId(), org, request, label, owner.getId());
                table = "llm_api_keys";
            }
            case DOMAIN -> {
                id = jdbc.queryForObject("""
                        insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at)
                        values(?,?,'EXTERNAL',?,'example.test','ACTIVE',now()+interval '30 days') returning id
                        """, Long.class, workspace.getId(), org, label + ".example.test");
                table = "domains";
            }
            case GPU -> {
                long request = jdbc.queryForObject("""
                        insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name)
                        values('GPU',?,?,?,'합성 확인 GPU',?) returning id
                        """, Long.class, workspace.getId(), org, owner.getId(), label);
                jdbc.update("insert into gpu_request_details(request_id,lease_hours) values(?,1)", request);
                id = jdbc.queryForObject("""
                        insert into gpu_allocations(request_id,workspace_id,org_id,name,granted_lease_hours)
                        values(?,?,?,?,1) returning id
                        """, Long.class, request, workspace.getId(), org, label);
                table = "gpu_allocations";
            }
            default -> throw new IllegalStateException(type.name());
        }
        ResourceAccessGrant ownerGrant = grants.saveAndFlush(ResourceAccessGrant.forUser(type, id, owner.getId(), ResourceRole.OWNER));
        return new Fixture(type, id, SeedFixtures.publicId(jdbc, table, id), workspace, ownerGrant.getPublicId());
    }

    private record Fixture(ResourceType type, long id, UUID publicId, Workspace workspace, UUID ownerGrantId) { }
}
