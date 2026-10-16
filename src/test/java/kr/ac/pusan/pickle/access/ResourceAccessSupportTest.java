package kr.ac.pusan.pickle.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.dto.AccessConfirmation;
import kr.ac.pusan.pickle.access.dto.ActorAccessImpact;
import kr.ac.pusan.pickle.access.dto.AddResourceAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.ResourceAccessChangePreviewRequest;
import kr.ac.pusan.pickle.access.dto.UpdateResourceAccessGrantRequest;
import kr.ac.pusan.pickle.admin.AdminUserProfileImpactService;
import kr.ac.pusan.pickle.admin.AdminUserService;
import kr.ac.pusan.pickle.admin.bulk.AdminBulkChangeService;
import kr.ac.pusan.pickle.admin.dto.AdminBulkAccessAction;
import kr.ac.pusan.pickle.admin.dto.AdminBulkAccessChange;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeKind;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeSpec;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeTargetType;
import kr.ac.pusan.pickle.admin.dto.AdminUpdateProfileRequest;
import kr.ac.pusan.pickle.admin.dto.AdminUserProfileImpactRequest;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationService;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersRequest;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Synthetic PostgreSQL fixtures exercise actual grant, claim, rollback and HTTP authority boundaries. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ResourceAccessSupportTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private ResourceAccessGrantRepository grants;
    @Autowired private ResourceAccessGrantService service;
    @Autowired private ResourceAccessResolver resolver;
    @Autowired private WorkspaceInvitationService invitations;
    @Autowired private AdminUserProfileImpactService profileImpacts;
    @Autowired private AdminUserService profileWrites;
    @Autowired private AdminBulkChangeService bulk;
    @Autowired private kr.ac.pusan.pickle.llm.LlmApiKeyService keys;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private ObjectMapper json;

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void everyAdapterRejectsAnUnconfirmedIncreaseAndAcceptsTheExactConfirmedGrade(ResourceType type) {
        Fixture fixture = fixture(type);
        User manager = user(UserRole.ORG_MANAGER);
        member(fixture.workspace(), manager, WorkspaceMemberRole.MEMBER);
        grants.saveAndFlush(ResourceAccessGrant.forUser(type, fixture.id(), manager.getId(), ResourceRole.MEMBER));
        UUID grantId = grants.findByResourceTypeAndResourceIdAndUserId(type, fixture.id(), manager.getId()).orElseThrow().getPublicId();
        var impact = service.preview(actor(manager), type, fixture.publicId(), change("UPDATE", grantId, null, null, ResourceRole.EDITOR), true);
        assertThat(impact.increases()).singleElement().satisfies(gain -> {
            assertThat(gain.beforeRole()).isEqualTo("MEMBER");
            assertThat(gain.afterRole()).isEqualTo("EDITOR");
            assertThat(gain.targetId()).isEqualTo(fixture.publicId());
        });
        long auditBefore = audits(fixture.publicId());
        long generationBefore = generation();
        assertThatThrownBy(() -> service.update(actor(manager), type, fixture.publicId(), grantId,
                new UpdateResourceAccessGrantRequest(ResourceRole.EDITOR), "127.0.8.1", true)).isInstanceOf(ApiException.class);
        assertThat(role(type, fixture.id(), manager)).isEqualTo(ResourceRole.MEMBER);
        assertThat(audits(fixture.publicId())).isEqualTo(auditBefore);
        assertThat(generation()).isEqualTo(generationBefore);
        service.update(actor(manager), type, fixture.publicId(), grantId,
                new UpdateResourceAccessGrantRequest(ResourceRole.EDITOR, confirmation(impact)), "127.0.8.1", true);
        assertThat(role(type, fixture.id(), manager)).isEqualTo(ResourceRole.EDITOR);
        assertThat(audits(fixture.publicId())).isEqualTo(auditBefore + 2);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and detail->>'purpose' = '합성 지원 목적'",
                Integer.class, fixture.publicId().toString())).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void stalePurposeTargetAndGradeConfirmationCannotMutateAnyAdapter(ResourceType type) {
        Fixture fixture = fixture(type);
        User manager = user(UserRole.ORG_ADMIN);
        member(fixture.workspace(), manager, WorkspaceMemberRole.MEMBER);
        var impact = service.preview(actor(manager), type, fixture.publicId(), change("ADD", null,
                AccessGranteeType.USER, manager.getPublicId(), ResourceRole.MEMBER), true);
        grants.saveAndFlush(ResourceAccessGrant.forOwningWorkspace(type, fixture.id(), ResourceRole.VIEWER));
        long auditBefore = audits(fixture.publicId());
        assertThatThrownBy(() -> service.add(actor(manager), type, fixture.publicId(),
                new AddResourceAccessGrantRequest(AccessGranteeType.USER, manager.getPublicId(), ResourceRole.MEMBER,
                        confirmation(impact)), "127.0.8.2", true)).isInstanceOf(ApiException.class);
        var fresh = service.preview(actor(manager), type, fixture.publicId(), change("ADD", null,
                AccessGranteeType.USER, manager.getPublicId(), ResourceRole.MEMBER), true);
        assertThatThrownBy(() -> service.add(actor(manager), type, fixture.publicId(),
                new AddResourceAccessGrantRequest(AccessGranteeType.USER, manager.getPublicId(), ResourceRole.OWNER,
                        confirmation(fresh)), "127.0.8.2", true)).isInstanceOf(ApiException.class);
        assertThat(grants.findByResourceTypeAndResourceIdAndUserId(type, fixture.id(), manager.getId())).isEmpty();
        assertThat(audits(fixture.publicId())).isEqualTo(auditBefore);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void supportEligibilityDoesNotRewriteOrdinaryUpdatesAndRevocationWorksForFormerAccounts(ResourceType type) {
        Fixture fixture = fixture(type);
        User inactive = user(UserRole.USER);
        member(fixture.workspace(), inactive, WorkspaceMemberRole.MEMBER);
        ResourceAccessGrant grant = grants.saveAndFlush(ResourceAccessGrant.forUser(type, fixture.id(), inactive.getId(), ResourceRole.MEMBER));
        jdbc.update("update users set status = 'DISABLED' where id = ?", inactive.getId());
        service.update(actor(fixture.owner()), type, fixture.publicId(), grant.getPublicId(),
                new UpdateResourceAccessGrantRequest(ResourceRole.EDITOR), "127.0.8.3");
        assertThat(role(type, fixture.id(), inactive)).isEqualTo(ResourceRole.EDITOR);
        User manager = user(UserRole.ORG_MANAGER);
        assertThatThrownBy(() -> service.update(actor(manager), type, fixture.publicId(), grant.getPublicId(),
                new UpdateResourceAccessGrantRequest(ResourceRole.OWNER), "127.0.8.3", true)).isInstanceOf(ApiException.class);
        jdbc.update("delete from workspace_members where workspace_id = ? and user_id = ?", fixture.workspace().getId(), inactive.getId());
        service.remove(actor(manager), type, fixture.publicId(), grant.getPublicId(), "127.0.8.3", true);
        assertThat(grants.findByPublicId(grant.getPublicId())).isEmpty();
    }

    @Test
    void sharedGrantsCompareTheEffectiveMaximumAndNeverGrantWorkspaceEditorRights() {
        Fixture fixture = fixture(ResourceType.VM);
        var owner = actor(fixture.owner());
        var first = service.preview(owner, ResourceType.VM, fixture.publicId(), change("ADD", null,
                AccessGranteeType.WORKSPACE, null, ResourceRole.MEMBER), false);
        assertThat(first.increases()).singleElement().satisfies(g -> assertThat(g.afterRole()).isEqualTo("MEMBER"));
        var shared = service.add(owner, ResourceType.VM, fixture.publicId(),
                new AddResourceAccessGrantRequest(AccessGranteeType.WORKSPACE, null, ResourceRole.MEMBER, confirmation(first)), "127.0.8.4");
        var self = service.preview(owner, ResourceType.VM, fixture.publicId(), change("ADD", null,
                AccessGranteeType.USER, fixture.owner().getPublicId(), ResourceRole.EDITOR), false);
        assertThat(self.increases()).singleElement().satisfies(g -> assertThat(g.beforeRole()).isEqualTo("MEMBER"));
        service.add(owner, ResourceType.VM, fixture.publicId(), new AddResourceAccessGrantRequest(
                AccessGranteeType.USER, fixture.owner().getPublicId(), ResourceRole.EDITOR, confirmation(self)), "127.0.8.4");
        assertThat(service.preview(owner, ResourceType.VM, fixture.publicId(), change("UPDATE", shared.id(), null, null,
                ResourceRole.VIEWER), false).requiresConfirmation()).isFalse();
        assertThatThrownBy(() -> service.update(owner, ResourceType.VM, fixture.publicId(), shared.id(),
                new UpdateResourceAccessGrantRequest(ResourceRole.OWNER), "127.0.8.4")).isInstanceOf(ApiException.class);
    }

    @Test
    void rollbackDoesNotPublishGrantOrBreakGlassAudits() {
        Fixture fixture = fixture(ResourceType.VM);
        User manager = user(UserRole.ORG_MANAGER);
        member(fixture.workspace(), manager, WorkspaceMemberRole.MEMBER);
        var impact = service.preview(actor(manager), ResourceType.VM, fixture.publicId(), change("ADD", null,
                AccessGranteeType.USER, manager.getPublicId(), ResourceRole.OWNER), true);
        long before = audits(fixture.publicId());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.add(actor(manager), ResourceType.VM, fixture.publicId(), new AddResourceAccessGrantRequest(
                    AccessGranteeType.USER, manager.getPublicId(), ResourceRole.OWNER, confirmation(impact)), "127.0.8.5", true);
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(grants.findByResourceTypeAndResourceIdAndUserId(ResourceType.VM, fixture.id(), manager.getId())).isEmpty();
        assertThat(audits(fixture.publicId())).isEqualTo(before);
    }

    @Test
    void supportSelfInvitationMustConfirmExistingSharedAccessBeforeMembershipInsertion() {
        Fixture fixture = fixture(ResourceType.VM);
        grants.saveAndFlush(ResourceAccessGrant.forOwningWorkspace(ResourceType.VM, fixture.id(), ResourceRole.MEMBER));
        User manager = user(UserRole.ORG_MANAGER);
        var request = new InviteWorkspaceMembersRequest(List.of(new InviteWorkspaceMembersRequest.WorkspaceInvitationEntry(manager.getEmail(), null)));
        var impact = invitations.previewInvite(actor(manager), fixture.workspace().getPublicId(), request, true);
        assertThat(impact.increases()).hasSize(2);
        assertThatThrownBy(() -> invitations.invite(actor(manager), fixture.workspace().getPublicId(), request, "127.0.8.6", true))
                .isInstanceOf(ApiException.class);
        assertThat(members.findByWorkspaceIdAndUserId(fixture.workspace().getId(), manager.getId())).isEmpty();
        invitations.invite(actor(manager), fixture.workspace().getPublicId(),
                new InviteWorkspaceMembersRequest(request.entries(), confirmation(impact)), "127.0.8.6", true);
        assertThat(resolver.standing(ResourceType.VM, fixture.id(), fixture.workspace().getId(), manager.getId()).role()).isEqualTo(ResourceRole.MEMBER);
    }

    @Test
    void privilegedSelfProfileClaimIsPureUntilItsCurrentAccessGainIsConfirmed() {
        Fixture fixture = fixture(ResourceType.VM);
        grants.saveAndFlush(ResourceAccessGrant.forOwningWorkspace(ResourceType.VM, fixture.id(), ResourceRole.VIEWER));
        User manager = user(UserRole.ORG_MANAGER);
        String studentNo = "9800000000" + manager.getId();
        jdbc.update("insert into workspace_invitations(workspace_id,invitee_student_no,role,status,invited_by) values(?,?,'MEMBER','PENDING',?)",
                fixture.workspace().getId(), studentNo, fixture.owner().getId());
        AdminUpdateProfileRequest correction = new AdminUpdateProfileRequest();
        correction.setPosition(UserPosition.STUDENT_UNDERGRAD); correction.setStudentNo(studentNo); correction.setDepartmentOther("합성 소속");
        var preview = profileImpacts.preview(actor(manager), manager.getPublicId(), new AdminUserProfileImpactRequest(
                AdminUserProfileImpactRequest.AdminUserProfileImpactAction.PROFILE, correction));
        assertThat(preview.actorAccessImpact().increases()).hasSize(2);
        assertThat(members.findByWorkspaceIdAndUserId(fixture.workspace().getId(), manager.getId())).isEmpty();
        assertThatThrownBy(() -> profileWrites.updateProfile(actor(manager), manager.getPublicId(), correction, "127.0.8.7"))
                .isInstanceOf(ApiException.class);
        assertThat(users.findById(manager.getId()).orElseThrow().getStudentNo()).isNull();
        correction.setConfirmation(confirmation(preview.actorAccessImpact()));
        profileWrites.updateProfile(actor(manager), manager.getPublicId(), correction, "127.0.8.7");
        assertThat(members.findByWorkspaceIdAndUserId(fixture.workspace().getId(), manager.getId())).isPresent();
        assertThat(users.findById(manager.getId()).orElseThrow().getStudentNo()).isEqualTo(studentNo);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void bulkAndSingleWritesShareTheActorGainConfirmationAndDuplicateSemantics(ResourceType type) {
        Fixture fixture = fixture(type);
        User manager = user(UserRole.ORG_MANAGER);
        member(fixture.workspace(), manager, WorkspaceMemberRole.MEMBER);
        AdminBulkChangeTargetType target = type == ResourceType.LLM_API_KEY ? AdminBulkChangeTargetType.LLM_KEY
                : type == ResourceType.GPU ? AdminBulkChangeTargetType.GPU_ALLOCATION : AdminBulkChangeTargetType.valueOf(type.name());
        var spec = new AdminBulkChangeSpec(AdminBulkChangeKind.ACCESS, null, null, null, null, null,
                null, null, null, null, new AdminBulkAccessChange(manager.getPublicId(), AdminBulkAccessAction.GRANT, ResourceRole.MEMBER));
        var request = new AdminBulkChangeRequest(target, List.of(fixture.publicId()), spec, null);
        var savedPreview = bulk.preview(actor(manager), request);
        var preview = savedPreview.items().getFirst();
        assertThat(preview.actorAccessImpact().increases()).singleElement();
        var fingerprints = Map.of(fixture.publicId(), preview.fingerprint());
        long auditsBefore = audits(fixture.publicId());
        assertThatThrownBy(() -> bulk.apply(actor(manager), new AdminBulkChangeRequest(target, request.targetIds(), spec,
                fingerprints, null, savedPreview.previewId(), java.util.UUID.randomUUID()), "127.0.8.8")).isInstanceOf(ApiException.class);
        assertThat(grants.findByResourceTypeAndResourceIdAndUserId(type, fixture.id(), manager.getId())).isEmpty();
        assertThat(audits(fixture.publicId())).isEqualTo(auditsBefore);
        var applied = bulk.apply(actor(manager), new AdminBulkChangeRequest(target, request.targetIds(), spec,
                fingerprints, Map.of(fixture.publicId(), confirmation(preview.actorAccessImpact())),
                savedPreview.previewId(), java.util.UUID.randomUUID()), "127.0.8.8");
        assertThat(applied.items().getFirst().result().name()).isEqualTo("APPLIED");
        assertThat(role(type, fixture.id(), manager)).isEqualTo(ResourceRole.MEMBER);
        assertThatThrownBy(() -> service.add(actor(manager), type, fixture.publicId(),
                new AddResourceAccessGrantRequest(AccessGranteeType.USER, manager.getPublicId(), ResourceRole.MEMBER),
                "127.0.8.8", true)).isInstanceOf(ApiException.class);
        assertThat(bulk.preview(actor(manager), request).items().getFirst().fields()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"USER", "ORG_VIEWER", "SYS_VIEWER", "SYS_MANAGER"})
    void newHttpSupportWritesRejectEveryReadonlyOrSystemManagerRole(UserRole role) throws Exception {
        Fixture fixture = fixture(ResourceType.VM);
        User reader = user(role);
        String path = "/api/v1/admin/resource-access/VM/" + fixture.publicId();
        mvc.perform(post(path).header("Authorization", "Bearer " + jwt.createAccessToken(reader))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("granteeType", "USER",
                        "userId", fixture.owner().getPublicId(), "role", "MEMBER"))))
                .andExpect(status().isForbidden());
        if (role != UserRole.USER) {
            String response = mvc.perform(get(path).header("Authorization", "Bearer " + jwt.createAccessToken(reader)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(response).get("canManageAccess").asBoolean()).isFalse();
        }
    }

    @Test
    void malformedOrdinaryAndSupportPreviewsReturnFieldValidationInsteadOfInternalErrors() throws Exception {
        Fixture fixture = fixture(ResourceType.VM);
        User manager = user(UserRole.ORG_MANAGER);
        for (String action : List.of("UPDATE", "REMOVE")) {
            Map<String, Object> body = Map.of("action", action, "role", "EDITOR");
            for (String path : List.of("/api/v1/vms/" + fixture.publicId() + "/access/preview",
                    "/api/v1/admin/resource-access/VM/" + fixture.publicId() + "/preview")) {
                User caller = path.contains("/admin/") ? manager : fixture.owner();
                String response = mvc.perform(post(path).header("Authorization", "Bearer " + jwt.createAccessToken(caller))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                        .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
                assertThat(json.readTree(response).get("errors").get(0).get("field").asString()).isEqualTo("grantId");
            }
        }
    }

    @Test
    void viewerGainIsConfirmedAndAuditedWhileContentGradeIncreasesCarryTheBreakGlassMarker() {
        Fixture fixture = fixture(ResourceType.VM);
        User owner = fixture.owner();
        var impact = service.preview(actor(owner), ResourceType.VM, fixture.publicId(), change("ADD", null,
                AccessGranteeType.USER, owner.getPublicId(), ResourceRole.VIEWER), false);
        assertThatThrownBy(() -> service.add(actor(owner), ResourceType.VM, fixture.publicId(),
                new AddResourceAccessGrantRequest(AccessGranteeType.USER, owner.getPublicId(), ResourceRole.VIEWER), "127.0.8.9"))
                .isInstanceOf(ApiException.class);
        var grant = service.add(actor(owner), ResourceType.VM, fixture.publicId(),
                new AddResourceAccessGrantRequest(AccessGranteeType.USER, owner.getPublicId(), ResourceRole.VIEWER, confirmation(impact)), "127.0.8.9");
        assertThat(audits(fixture.publicId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and action = 'vm.access_break_glass'",
                Integer.class, fixture.publicId().toString())).isZero();
        for (ResourceRole next : List.of(ResourceRole.MEMBER, ResourceRole.EDITOR, ResourceRole.OWNER)) {
            var gain = service.preview(actor(owner), ResourceType.VM, fixture.publicId(), change("UPDATE", grant.id(), null, null, next), false);
            service.update(actor(owner), ResourceType.VM, fixture.publicId(), grant.id(),
                    new UpdateResourceAccessGrantRequest(next, confirmation(gain)), "127.0.8.9");
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and action = 'vm.access_break_glass'",
                Integer.class, fixture.publicId().toString())).isEqualTo(3);
    }

    @Test
    void foreignInstitutionAdminRetainsTheirUserOwnerGrantAndWorkspaceStandingOnLlmRevocation() {
        for (boolean workspaceOwner : List.of(false, true)) {
            Fixture fixture = fixture(ResourceType.LLM_API_KEY);
            User admin = user(UserRole.ORG_ADMIN);
            member(fixture.workspace(), admin, workspaceOwner ? WorkspaceMemberRole.OWNER : WorkspaceMemberRole.MEMBER);
            if (!workspaceOwner) { grants.saveAndFlush(ResourceAccessGrant.forUser(ResourceType.LLM_API_KEY, fixture.id(), admin.getId(), ResourceRole.OWNER)); }
            long foreignOrg = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "합성 별도 기관");
            jdbc.update("delete from user_org_roles where user_id = ?", admin.getId());
            SeedFixtures.grantOrgRole(jdbc, admin.getId(), foreignOrg, UserRole.ORG_ADMIN);
            var foreignActor = new AuthenticatedUser(admin.getId(), admin.getPublicId(), admin.getEmail(), UserRole.ORG_ADMIN,
                    Map.of(foreignOrg, UserRole.ORG_ADMIN));
            keys.revoke(foreignActor, fixture.publicId());
            assertThat(jdbc.queryForObject("select status::text from llm_api_keys where id = ?", String.class, fixture.id())).isEqualTo("REVOKED");
        }
    }

    @Test
    void llmActualInstitutionOverrideSurvivesWithoutAddingManagerDirectRevocation() {
        Fixture allowed = fixture(ResourceType.LLM_API_KEY);
        long org = jdbc.queryForObject("select org_id from llm_api_keys where id = ?", Long.class, allowed.id());
        User admin = user(UserRole.ORG_ADMIN);
        keys.revoke(new AuthenticatedUser(admin.getId(), admin.getPublicId(), admin.getEmail(), UserRole.ORG_ADMIN,
                Map.of(org, UserRole.ORG_ADMIN)), allowed.publicId());
        assertThat(jdbc.queryForObject("select status::text from llm_api_keys where id = ?", String.class, allowed.id())).isEqualTo("REVOKED");
        Fixture denied = fixture(ResourceType.LLM_API_KEY);
        User manager = user(UserRole.ORG_MANAGER);
        assertThatThrownBy(() -> keys.revoke(new AuthenticatedUser(manager.getId(), manager.getPublicId(), manager.getEmail(),
                UserRole.ORG_MANAGER, Map.of(org, UserRole.ORG_MANAGER)), denied.publicId())).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select status::text from llm_api_keys where id = ?", String.class, denied.id())).isEqualTo("ACTIVE");
    }

    @Test
    void aPrivilegedSelfClaimConfirmsFutureQueuedOwnershipWithoutReportingAResourceAsCreated() {
        Fixture fixture = fixture(ResourceType.VM);
        User manager = user(UserRole.ORG_MANAGER);
        String studentNo = "9700000000" + manager.getId();
        long invitation = jdbc.queryForObject("insert into workspace_invitations(workspace_id,invitee_student_no,role,invited_by) values(?,?,'MEMBER',?) returning id",
                Long.class, fixture.workspace().getId(), studentNo, fixture.owner().getId());
        long org = jdbc.queryForObject("select org_id from vms where id = ?", Long.class, fixture.id());
        long request = RequestFixtures.insertVmRequest(jdbc, fixture.workspace().getId(), org, fixture.owner().getId(), "합성 생성 대기", null);
        RequestFixtures.approveVmRequest(jdbc, request, fixture.owner().getId(), null, 2, 2048, 10);
        jdbc.update("update requests set status = 'APPROVED' where id = ?", request);
        jdbc.update("insert into request_recipients(public_id,request_id,invitation_id,status) values(?,?,?,'PENDING_JOIN')",
                UUID.randomUUID(), request, invitation);
        AdminUpdateProfileRequest correction = new AdminUpdateProfileRequest();
        correction.setPosition(UserPosition.STUDENT_UNDERGRAD); correction.setStudentNo(studentNo); correction.setDepartmentOther("합성 소속");
        var impact = profileImpacts.preview(actor(manager), manager.getPublicId(), new AdminUserProfileImpactRequest(
                AdminUserProfileImpactRequest.AdminUserProfileImpactAction.PROFILE, correction)).actorAccessImpact();
        assertThat(impact.pendingResourceOwners()).singleElement().satisfies(pending -> {
            assertThat(pending.projectedStatus()).isEqualTo("QUEUED"); assertThat(pending.role()).isEqualTo("OWNER");
            assertThat(pending.requestId()).isEqualTo(SeedFixtures.publicId(jdbc, "requests", request));
        });
        assertThat(impact.increases()).allSatisfy(gain -> assertThat(gain.resourceType()).isNull());
        assertThatThrownBy(() -> profileWrites.updateProfile(actor(manager), manager.getPublicId(), correction, "127.0.8.10"))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select status from request_recipients where request_id = ?", String.class, request)).isEqualTo("PENDING_JOIN");
        correction.setConfirmation(confirmation(impact));
        profileWrites.updateProfile(actor(manager), manager.getPublicId(), correction, "127.0.8.10");
        assertThat(jdbc.queryForObject("select status from request_recipients where request_id = ?", String.class, request)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from vms where request_id = ?", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where actor_id = ? and action = 'user.profile_update' and jsonb_array_length(detail->'pendingResourceOwners') = 1",
                Integer.class, manager.getId())).isEqualTo(1);
    }

    @Test
    void supportAddAndChangeRejectAnActiveAccountWhoseWorkspaceMembershipHasEnded() {
        Fixture fixture = fixture(ResourceType.VM);
        User former = user(UserRole.USER);
        ResourceAccessGrant original = grants.saveAndFlush(ResourceAccessGrant.forUser(ResourceType.VM, fixture.id(), former.getId(), ResourceRole.MEMBER));
        User manager = user(UserRole.ORG_MANAGER);
        assertThatThrownBy(() -> service.update(actor(manager), ResourceType.VM, fixture.publicId(), original.getPublicId(),
                new UpdateResourceAccessGrantRequest(ResourceRole.EDITOR), "127.0.8.11", true)).isInstanceOf(ApiException.class);
        assertThat(role(ResourceType.VM, fixture.id(), former)).isEqualTo(ResourceRole.MEMBER);
        assertThatThrownBy(() -> service.add(actor(manager), ResourceType.VM, fixture.publicId(),
                new AddResourceAccessGrantRequest(AccessGranteeType.USER, former.getPublicId(), ResourceRole.EDITOR), "127.0.8.11", true))
                .isInstanceOf(ApiException.class);
        assertThat(audits(fixture.publicId())).isZero();
    }

    @Test
    void privilegedClaimRollbackLeavesProfileMembershipRecipientAuditAndNotificationsUnchanged() {
        Fixture fixture = fixture(ResourceType.VM);
        User manager = user(UserRole.ORG_MANAGER);
        String studentNo = "9600000000" + manager.getId();
        long invitation = jdbc.queryForObject("insert into workspace_invitations(workspace_id,invitee_student_no,role,invited_by) values(?,?,'MEMBER',?) returning id",
                Long.class, fixture.workspace().getId(), studentNo, fixture.owner().getId());
        long org = jdbc.queryForObject("select org_id from vms where id = ?", Long.class, fixture.id());
        long request = RequestFixtures.insertVmRequest(jdbc, fixture.workspace().getId(), org, fixture.owner().getId(), "합성 rollback 대기", null);
        RequestFixtures.approveVmRequest(jdbc, request, fixture.owner().getId(), null, 2, 2048, 10);
        jdbc.update("update requests set status = 'APPROVED' where id = ?", request);
        jdbc.update("insert into request_recipients(public_id,request_id,invitation_id,status) values(?,?,?,'PENDING_JOIN')", UUID.randomUUID(), request, invitation);
        AdminUpdateProfileRequest correction = new AdminUpdateProfileRequest();
        correction.setPosition(UserPosition.STUDENT_UNDERGRAD); correction.setStudentNo(studentNo); correction.setDepartmentOther("합성 소속");
        var impact = profileImpacts.preview(actor(manager), manager.getPublicId(), new AdminUserProfileImpactRequest(
                AdminUserProfileImpactRequest.AdminUserProfileImpactAction.PROFILE, correction)).actorAccessImpact();
        correction.setConfirmation(confirmation(impact));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            profileWrites.updateProfile(actor(manager), manager.getPublicId(), correction, "127.0.8.12");
            throw new IllegalStateException("synthetic claim rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(manager.getId()).orElseThrow().getStudentNo()).isNull();
        assertThat(members.findByWorkspaceIdAndUserId(fixture.workspace().getId(), manager.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select status::text from workspace_invitations where id = ?", String.class, invitation)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from request_recipients where request_id = ?", String.class, request)).isEqualTo("PENDING_JOIN");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where actor_id = ?", Integer.class, manager.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ?", Integer.class, manager.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from vms where request_id = ?", Integer.class, request)).isZero();
    }

    private Fixture fixture(ResourceType type) {
        User owner = user(UserRole.USER);
        Workspace workspace = workspaces.saveAndFlush(new Workspace(WorkspaceKind.PROJECT, "합성 접근 " + UUID.randomUUID(), null));
        member(workspace, owner, WorkspaceMemberRole.OWNER);
        long orgId = jdbc.queryForObject("select min(id) from orgs", Long.class);
        String label = "support-" + UUID.randomUUID().toString().substring(0, 8);
        long id;
        String table;
        switch (type) {
            case VM -> {
                long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
                long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
                long request = RequestFixtures.insertVmRequest(jdbc, workspace.getId(), orgId, owner.getId(), "합성 접근", image);
                id = jdbc.queryForObject("insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status) values(?,?,?,?,?,?,?,1,1024,10,'CREATING') returning id",
                        Long.class, node, workspace.getId(), orgId, request, label, label, image); table = "vms";
            }
            case LLM_API_KEY -> {
                long request = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('LLM_API_KEY',?,?,?,'합성 접근',?) returning id",
                        Long.class, workspace.getId(), orgId, owner.getId(), label);
                jdbc.update("insert into llm_key_request_details(request_id,req_passthrough_endpoints) values(?, '[\"chat\"]')", request);
                id = jdbc.queryForObject("insert into llm_api_keys(workspace_id,org_id,request_id,name,purpose,token_hash,token_prefix,credit_limit,created_by,status) values(?,?,?,?,'합성 접근',?,?,0,?,'ACTIVE') returning id",
                        Long.class, workspace.getId(), orgId, request, label, UUID.randomUUID().toString().replace("-", ""), label, owner.getId()); table = "llm_api_keys";
            }
            case DOMAIN -> {
                id = jdbc.queryForObject("insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at) values(?,?,'EXTERNAL',?,'example.test','ACTIVE',now()+interval '10 days') returning id",
                        Long.class, workspace.getId(), orgId, label + ".example.test"); table = "domains";
            }
            case GPU -> {
                long request = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('GPU',?,?,?,'합성 접근',?) returning id",
                        Long.class, workspace.getId(), orgId, owner.getId(), label);
                id = jdbc.queryForObject("insert into gpu_allocations(request_id,workspace_id,org_id,name,granted_lease_hours) values(?,?,?,?,1) returning id",
                        Long.class, request, workspace.getId(), orgId, label); table = "gpu_allocations";
            }
            default -> throw new IllegalStateException(type.name());
        }
        return new Fixture(type, id, SeedFixtures.publicId(jdbc, table, id), workspace, owner);
    }

    private User user(UserRole role) {
        User user = new User("support-access." + UUID.randomUUID() + "@pusan.ac.kr", "{synthetic-no-login}", "합성 접근 수행자");
        user.setStatus(UserStatus.ACTIVE); user.setEmailVerifiedAt(Instant.now()); user.setRole(role);
        user = users.saveAndFlush(user);
        if (role.isOrgTier()) { SeedFixtures.grantOrgRole(jdbc, user.getId(), jdbc.queryForObject("select min(id) from orgs", Long.class), role); }
        return user;
    }
    private void member(Workspace workspace, User user, WorkspaceMemberRole role) {
        members.saveAndFlush(new WorkspaceMember(workspace, user.getId(), role));
    }
    private AuthenticatedUser actor(User user) {
        return new AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(), user.getRole(), Map.of());
    }
    private static AccessConfirmation confirmation(ActorAccessImpact impact) { return new AccessConfirmation("합성 지원 목적", impact.fingerprint(), true); }
    private static ResourceAccessChangePreviewRequest change(String action, UUID grantId, AccessGranteeType grantee, UUID userId, ResourceRole role) {
        return new ResourceAccessChangePreviewRequest(ResourceAccessChangePreviewRequest.Action.valueOf(action), grantId, grantee, userId, role);
    }
    private ResourceRole role(ResourceType type, long id, User user) {
        return grants.findByResourceTypeAndResourceIdAndUserId(type, id, user.getId()).orElseThrow().getRole();
    }
    private long audits(UUID resourceId) { return jdbc.queryForObject("select count(*) from audit_logs where target_id = ?", Long.class, resourceId.toString()); }
    private long generation() { return jdbc.queryForObject("select coalesce((select generation from llm_gateway_state where id), 0)", Long.class); }
    private record Fixture(ResourceType type, long id, UUID publicId, Workspace workspace, User owner) { }
}
