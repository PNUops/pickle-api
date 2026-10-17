package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
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
import tools.jackson.databind.ObjectMapper;

/** Support reads span institutions and observe actual claim outcomes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminUserSupportTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private JwtService tokens;
    @Autowired private ObjectMapper json;

    private User target;
    private User owner;
    private User sysAdmin;
    private long org;
    private long foreignOrg;
    private long workspace;

    @BeforeEach
    void setUp() {
        target = user(UserRole.USER, UserStatus.ACTIVE);
        owner = user(UserRole.USER, UserStatus.ACTIVE);
        sysAdmin = users.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        org = SeedFixtures.seedOrgId(jdbc);
        foreignOrg = jdbc.queryForObject("insert into orgs (name) values (?) returning id", Long.class, "지원 타 기관 " + UUID.randomUUID());
        workspace = workspace();
    }

    @Test
    void allSixReadRolesReadGlobalRelationsAndResourceResults() throws Exception {
        member(workspace, target, "OWNER");
        long ownVm = vm(workspace, org);
        long foreignVm = vm(workspace, foreignOrg);
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN,
                UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)) {
            User actor = actor(role);
            read(actor, "").andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships[0].workspaceId").value(pub("workspaces", workspace).toString()))
                    .andExpect(jsonPath("$.resources.length()").value(2))
                    .andExpect(jsonPath("$.resources[?(@.id == '%s')]".formatted(pub("vms", ownVm))).exists())
                    .andExpect(jsonPath("$.requests.length()").value(2));
            if (role.isOrgTier()) {
                diagnose(actor, foreignVm).andExpect(status().isOk());
            }
        }
        read(target, "").andExpect(status().isForbidden());
        mvc.perform(get(path(target) + "/support")).andExpect(status().isUnauthorized());
    }

    @Test
    void globalWorkspaceAccessWritersCanRevokeAcrossInstitutions() throws Exception {
        member(workspace, target, "MEMBER");
        long first = vm(workspace, org);
        long second = vm(workspace, foreignOrg);
        grant(first, target, "EDITOR");
        grant(second, target, "EDITOR");
        User actor = actor(UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbc, actor.getId(), foreignOrg, UserRole.ORG_VIEWER);
        read(actor, "").andExpect(status().isOk()).andExpect(jsonPath("$.resources.length()").value(2));
        diagnose(actor, first).andExpect(status().isOk()).andExpect(jsonPath("$.canRevoke").value(true));
        diagnose(actor, second).andExpect(status().isOk()).andExpect(jsonPath("$.canRevoke").value(true));
        read(actor, "?orgId=" + pub("orgs", org)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(1));
        read(actor(UserRole.ORG_VIEWER), "?orgId=" + pub("orgs", foreignOrg)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resources.length()").value(1));
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"ORG_MANAGER", "ORG_ADMIN", "SYS_ADMIN", "SYS_MANAGER", "ORG_VIEWER", "SYS_VIEWER"})
    void residualGrantRevocationCapabilitiesMatchActualGlobalAccessPolicy(UserRole role) throws Exception {
        long resource = vm(workspace, foreignOrg);
        grant(resource, target, "EDITOR");
        jdbc.update("insert into resource_access_grants(resource_type,resource_id,grantee_type,role) values('VM',?,'WORKSPACE','MEMBER')", resource);
        jdbc.update("update users set status='DISABLED' where id=?", target.getId());
        User actor = actor(role);
        boolean allowed = role == UserRole.ORG_MANAGER || role == UserRole.ORG_ADMIN || role == UserRole.SYS_ADMIN;
        UUID resourceId = pub("vms", resource);
        UUID grantId = jdbc.queryForObject("select public_id from resource_access_grants where resource_type='VM' and resource_id=? and user_id=?",
                UUID.class, resource, target.getId());
        diagnose(actor, resource).andExpect(status().isOk())
                .andExpect(jsonPath("$.canRevoke").value(allowed))
                .andExpect(jsonPath("$.revokeReason").value(allowed ? "ALLOWED" : "FORBIDDEN"))
                .andExpect(jsonPath("$.workspaceRole").isEmpty());
        mvc.perform(get("/api/v1/admin/resource-access/VM/" + resourceId).header("Authorization", "Bearer " + token(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canManageAccess").value(allowed));
        ResultActions removed = mvc.perform(delete("/api/v1/admin/resource-access/VM/" + resourceId + "/" + grantId)
                .header("Authorization", "Bearer " + token(actor)));
        if (allowed) {
            removed.andExpect(status().isNoContent());
            diagnose(actor, resource).andExpect(status().isOk()).andExpect(jsonPath("$.canRevoke").value(false))
                    .andExpect(jsonPath("$.revokeReason").value("NO_PERSONAL_GRANT"));
        } else { removed.andExpect(status().isForbidden()); }
        assertThat(jdbc.queryForObject("select count(*) from resource_access_grants where public_id=?", Long.class, grantId))
                .isEqualTo(allowed ? 0L : 1L);
        assertThat(jdbc.queryForObject("select count(*) from resource_access_grants where resource_type='VM' and resource_id=? and grantee_type='WORKSPACE'", Long.class, resource)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where actor_id=? and action='vm.access_grant_remove' and target_id=?",
                Long.class, actor.getId(), resourceId.toString())).isEqualTo(allowed ? 1L : 0L);
    }

    @Test
    void removedDomainsAndMissingPersonalGrantsRemainIneligibleForDiagnosticRevocation() throws Exception {
        long resource = vm(workspace, foreignOrg);
        diagnose(actor(UserRole.ORG_MANAGER), resource).andExpect(status().isOk())
                .andExpect(jsonPath("$.canRevoke").value(false)).andExpect(jsonPath("$.revokeReason").value("NO_PERSONAL_GRANT"));
        long removed = domain(workspace, foreignOrg);
        jdbc.update("update domains set status='REMOVED' where id=?", removed);
        jdbc.update("insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role) values('DOMAIN',?,'USER',?,'EDITOR')", removed, target.getId());
        UUID resourceId = pub("domains", removed);
        UUID grantId = jdbc.queryForObject("select public_id from resource_access_grants where resource_type='DOMAIN' and resource_id=? and user_id=?",
                UUID.class, removed, target.getId());
        mvc.perform(get(path(target) + "/support/access").param("type", "DOMAIN").param("resourceId", resourceId.toString())
                .header("Authorization", "Bearer " + token(sysAdmin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.canRevoke").value(false)).andExpect(jsonPath("$.revokeReason").value("RESOURCE_NOT_FOUND"));
        // The diagnostic describes the support page's bulk consumer. The
        // thin single path separately keeps historical cleanup behavior.
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("targetType", "DOMAIN");
        body.put("targetIds", List.of(resourceId.toString()));
        body.put("change", Map.of("kind", "ACCESS", "access", Map.of("action", "REVOKE", "userId", target.getPublicId().toString())));
        var previewed = json.readTree(mvc.perform(post("/api/v1/admin/bulk-changes/preview")
                        .header("Authorization", "Bearer " + token(sysAdmin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(previewed.get("items").get(0).get("applicable").asBoolean()).isFalse();
        assertThat(previewed.get("items").get(0).get("reason").asString()).isEqualTo("NOT_FOUND");
        body.put("previewId", previewed.get("previewId").asString());
        body.put("operationId", UUID.randomUUID().toString());
        body.put("fingerprints", Map.of(resourceId.toString(), previewed.get("items").get(0).get("fingerprint").asString()));
        var refused = json.readTree(mvc.perform(post("/api/v1/admin/bulk-changes")
                        .header("Authorization", "Bearer " + token(sysAdmin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(refused.get("items").get(0).get("result").asString()).isEqualTo("SKIPPED");
        assertThat(refused.get("items").get(0).get("reason").asString()).isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from resource_access_grants where public_id=?", Long.class, grantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='domain.access_grant_remove' and target_id=?", Long.class, resourceId.toString())).isZero();
    }

    @Test
    void exactResourceExplainsNonMembershipAndOwnerStandingWithoutGrant() throws Exception {
        long resource = vm(workspace, org);
        diagnose(sysAdmin, resource).andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons[0]").value("NOT_WORKSPACE_MEMBER"))
                .andExpect(jsonPath("$.effectiveRole").isEmpty())
                .andExpect(jsonPath("$.baseConditionsSatisfied").value(false));
        member(workspace, target, "OWNER");
        diagnose(sysAdmin, resource).andExpect(status().isOk())
                .andExpect(jsonPath("$.standingRights").value(true))
                .andExpect(jsonPath("$.effectiveRole").isEmpty())
                .andExpect(jsonPath("$.baseConditionsSatisfied").value(false));
        grant(resource, target, "VIEWER");
        jdbc.update("insert into resource_access_grants (resource_type,resource_id,grantee_type,role) values ('VM',?,'WORKSPACE','MEMBER')", resource);
        diagnose(sysAdmin, resource).andExpect(status().isOk())
                .andExpect(jsonPath("$.personalGrantRole").value("VIEWER"))
                .andExpect(jsonPath("$.workspaceGrantRole").value("MEMBER"))
                .andExpect(jsonPath("$.effectiveRole").value("MEMBER"))
                .andExpect(jsonPath("$.baseConditionsSatisfied").value(true));
    }

    @Test
    void inactiveResidualNamedGrantIsFindableWithoutMembership() throws Exception {
        long resource = vm(workspace, org);
        grant(resource, target, "EDITOR");
        jdbc.update("update users set status='DISABLED' where id=?", target.getId());
        read(sysAdmin, "").andExpect(status().isOk()).andExpect(jsonPath("$.resources.length()").value(1))
                .andExpect(jsonPath("$.resources[0].canRevoke").value(true))
                .andExpect(jsonPath("$.resources[0].effectiveRole").isEmpty())
                .andExpect(jsonPath("$.resources[0].reasons[0]").value("ACCOUNT_INACTIVE"));
    }

    @Test
    void missingTargetsEmptyResultsAndSystemAccountMaskStayDistinct() throws Exception {
        read(sysAdmin, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.resources").isEmpty()).andExpect(jsonPath("$.requests").isEmpty());
        mvc.perform(get("/api/v1/admin/users/" + SeedFixtures.UNKNOWN_ID + "/support")
                .header("Authorization", "Bearer " + token(sysAdmin))).andExpect(status().isNotFound());
        mvc.perform(get(path(sysAdmin) + "/support").header("Authorization", "Bearer " + token(actor(UserRole.ORG_ADMIN))))
                .andExpect(status().isNotFound());
        read(sysAdmin, "?orgId=" + SeedFixtures.UNKNOWN_ID).andExpect(status().isOk())
                .andExpect(jsonPath("$.resources").isEmpty());
    }

    @Test
    void allSupportReadersResolvePendingAndClaimedInvitationIdentities() throws Exception {
        String studentNo = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        target.setProfile(UserPosition.STUDENT_UNDERGRAD, studentNo, null, null);
        target.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(target);
        long invitation = invitation(studentNo, null);
        long request = request(workspace, org);
        recipient(request, invitation);
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN,
                UserRole.SYS_VIEWER, UserRole.SYS_MANAGER)) {
            read(actor(role), "").andExpect(status().isOk())
                    .andExpect(jsonPath("$.invitationsVisible").value(true))
                    .andExpect(jsonPath("$.invitations.length()").value(1))
                    .andExpect(jsonPath("$.requests.length()").value(1));
        }
        long emailInvitation = invitation(null, target.getEmail());
        long emailRequest = request(workspace, org);
        recipient(emailRequest, emailInvitation);
        read(actor(UserRole.ORG_MANAGER), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.requests[?(@.id == '%s')]".formatted(pub("requests", emailRequest))).exists())
                .andExpect(jsonPath("$.invitations.length()").value(2));
        read(sysAdmin, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(2)).andExpect(jsonPath("$.invitations.length()").value(2));
        mvc.perform(post(path(target) + "/enable").header("Authorization", "Bearer " + token(sysAdmin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        read(actor(UserRole.ORG_VIEWER), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].recipientStatus").value("QUEUED"));
    }

    @Test
    void previewDoesNotClaimAndCorrectionConnectsActualQueuedAndCreatedResults() throws Exception {
        String studentNo = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        long invitation = invitation(studentNo, null);
        long request = request(workspace, org);
        RequestFixtures.approveVmRequest(jdbc, request, sysAdmin.getId(), null, 2, 2048, 10);
        jdbc.update("update requests set status='APPROVED' where id=?", request);
        recipient(request, invitation);
        Map<String, Object> proposed = Map.of("position", "STUDENT_UNDERGRAD", "studentNo", " " + studentNo.toLowerCase() + " ");
        long queuedBefore = jdbc.queryForObject("select count(*) from jobrunr_jobs", Long.class);
        long recipientsBefore = jdbc.queryForObject("select count(*) from request_recipients", Long.class);
        preview(actor(UserRole.ORG_MANAGER), Map.of("action", "PROFILE", "profile", proposed)).andExpect(status().isOk())
                .andExpect(jsonPath("$.claimsPossible").value(true))
                .andExpect(jsonPath("$.invitations[0].recipients[0].projectedStatus").value("QUEUED"))
                .andExpect(jsonPath("$.invitations[0].recipients[0].orgId").value(pub("orgs", org).toString()));
        assertThat(jdbc.queryForObject("select status from workspace_invitations where id=?", String.class, invitation)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from workspace_members where workspace_id=? and user_id=?", Long.class, workspace, target.getId())).isZero();
        assertThat(users.findById(target.getId()).orElseThrow().getStudentNo()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from jobrunr_jobs", Long.class)).isEqualTo(queuedBefore);
        assertThat(jdbc.queryForObject("select count(*) from request_recipients", Long.class)).isEqualTo(recipientsBefore);
        mvc.perform(patch(path(target) + "/profile").header("Authorization", "Bearer " + token(actor(UserRole.ORG_ADMIN)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(proposed))).andExpect(status().isOk());
        read(sysAdmin, "").andExpect(status().isOk()).andExpect(jsonPath("$.invitations[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.requests[0].recipientStatus").value("QUEUED"))
                .andExpect(jsonPath("$.requests[0].resourceId").isEmpty());
        long resource = vm(workspace, org, request);
        jdbc.update("update request_recipients set status='CREATED', resource_id=? where request_id=?", resource, request);
        read(sysAdmin, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].recipientStatus").value("CREATED"))
                .andExpect(jsonPath("$.requests[0].resourceId").value(pub("vms", resource).toString()))
                .andExpect(jsonPath("$.resources[0].status").value("RUNNING"));
    }

    @Test
    void restorationNeverBypassesVerificationAndUnchangedProfileNeverClaims() throws Exception {
        long invitation = invitation(null, target.getEmail());
        jdbc.update("update users set status='DISABLED' where id=?", target.getId());
        jdbc.update("insert into user_status_changes (user_id,from_status,to_status,actor_id) values (?,'PENDING_VERIFICATION','DISABLED',?)", target.getId(), sysAdmin.getId());
        preview(sysAdmin, Map.of("action", "ENABLE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateStatus").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.claimsPossible").value(false)).andExpect(jsonPath("$.invitations.length()").value(1));
        assertThat(jdbc.queryForObject("select status from workspace_invitations where id=?", String.class, invitation)).isEqualTo("PENDING");
        preview(sysAdmin, Map.of("action", "PROFILE", "profile", Map.of("departmentOther", "지원 소속")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.claimsPossible").value(false))
                .andExpect(jsonPath("$.invitations").isEmpty());
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN, UserRole.SYS_VIEWER, UserRole.SYS_MANAGER)) {
            preview(actor(role), Map.of("action", "ENABLE")).andExpect(status().isForbidden());
        }
    }

    @Test
    void expiredApprovedRecipientIsPreviewedAsSkippedRatherThanCreated() throws Exception {
        String studentNo = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        long invitation = invitation(studentNo, null);
        long request = request(workspace, org);
        RequestFixtures.approveVmRequest(jdbc, request, sysAdmin.getId(), null, 2, 2048, 10);
        jdbc.update("update request_reviews set granted_end_date=? where request_id=?", LocalDate.now(ClockConfig.KST).minusDays(1), request);
        jdbc.update("update requests set status='APPROVED' where id=?", request);
        recipient(request, invitation);
        preview(sysAdmin, Map.of("action", "PROFILE", "profile", Map.of("position", "STUDENT_UNDERGRAD", "studentNo", studentNo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invitations[0].recipients[0].projectedStatus").value("SKIPPED_EXPIRED"));
    }

    @Test
    void workspaceRequestAndDomainFiltersCountGlobalMatchingRows() throws Exception {
        long other = workspace();
        request(workspace, org);
        request(workspace, foreignOrg);
        request(other, org);
        domain(workspace, org);
        domain(workspace, foreignOrg);
        domain(other, org);
        String token = token(actor(UserRole.ORG_VIEWER));
        for (String list : List.of("requests", "domains")) {
            mvc.perform(get("/api/v1/admin/" + list).param("workspaceId", pub("workspaces", workspace).toString())
                    .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content.length()").value(2));
            mvc.perform(get("/api/v1/admin/" + list).param("workspaceId", SeedFixtures.UNKNOWN_ID.toString())
                    .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    @Test
    void allResourceTypesKeepActualStatusesAndDomainCountsIncludeVmNames() throws Exception {
        member(workspace, target, "MEMBER");
        long resource = vm(workspace, foreignOrg);
        long externalDomain = domain(workspace, foreignOrg);
        jdbc.update("insert into domains(workspace_id,org_id,vm_id,kind,fqdn,status,verification_token) values(?,?,?,'CUSTOM',?,'PENDING','support-fixture-token')",
                workspace, foreignOrg, resource, "custom-" + UUID.randomUUID() + ".example.com");
        long keyRequest = RequestFixtures.insertLlmKeyRequest(jdbc, workspace, foreignOrg, owner.getId(), "지원 LLM");
        long key = jdbc.queryForObject("insert into llm_api_keys(workspace_id,org_id,request_id,name,created_by) values(?,?,?,'지원 키',?) returning id", Long.class, workspace, foreignOrg, keyRequest, owner.getId());
        long gpuRequest = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('GPU',?,?,?,'지원 GPU','지원 GPU') returning id",
                Long.class, workspace, foreignOrg, owner.getId());
        jdbc.update("insert into gpu_request_details(request_id,lease_hours) values(?,1)", gpuRequest);
        long allocation = jdbc.queryForObject("insert into gpu_allocations(request_id,workspace_id,org_id,name,granted_lease_hours) values(?,?,?,'지원 GPU',1) returning id", Long.class, gpuRequest, workspace, foreignOrg);
        Map<String, UUID> resources = Map.of("vms", pub("vms", resource), "llm/keys", pub("llm_api_keys", key),
                "domains", pub("domains", externalDomain), "gpu-allocations", pub("gpu_allocations", allocation));
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN)) {
            read(actor(role), "").andExpect(status().isOk()).andExpect(jsonPath("$.resources.length()").value(4))
                .andExpect(jsonPath("$.resources[?(@.type == 'LLM_API_KEY')].status").value("PENDING"))
                .andExpect(jsonPath("$.resources[?(@.type == 'GPU')].status").value("QUEUED"))
                .andExpect(jsonPath("$.memberships[0].resourceCounts[?(@.type == 'DOMAIN')].count").value(2));
            String actorToken = token(actor(role));
            for (Map.Entry<String, UUID> entry : resources.entrySet()) {
                String path = "/api/v1/admin/" + entry.getKey();
                mvc.perform(get(path).param("workspaceId", pub("workspaces", workspace).toString())
                                .header("Authorization", "Bearer " + actorToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(entry.getValue())).exists());
                mvc.perform(get(path + "/" + entry.getValue()).header("Authorization", "Bearer " + actorToken))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(entry.getValue().toString()));
            }
        }
    }

    @Test
    void activeRestorationPreviewsEmailAndStudentClaimsWithoutDoingEither() throws Exception {
        String studentNo = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        target.setProfile(UserPosition.STUDENT_UNDERGRAD, studentNo, null, null);
        target.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(target);
        invitation(studentNo, null);
        invitation(null, target.getEmail());
        jdbc.update("insert into user_status_changes(user_id,from_status,to_status,actor_id) values(?,'ACTIVE','DISABLED',?)", target.getId(), sysAdmin.getId());
        preview(sysAdmin, Map.of("action", "ENABLE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.claimsPossible").value(true))
                .andExpect(jsonPath("$.invitations.length()").value(2))
                .andExpect(jsonPath("$.invitations[0].matchedBy").value("EMAIL"));
        assertThat(jdbc.queryForObject("select count(*) from workspace_invitations where workspace_id=? and status='ACCEPTED'", Long.class, workspace)).isZero();
    }

    @Test
    void acceptedInvitationsKeepDuplicateRecipientsLinkedToTheActualAccount() throws Exception {
        String studentNo = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        target.setProfile(UserPosition.STUDENT_UNDERGRAD, studentNo, null, null);
        target.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(target);
        long studentInvitation = invitation(studentNo, null);
        long emailInvitation = invitation(null, target.getEmail());
        long request = request(workspace, org);
        recipient(request, studentInvitation);
        recipient(request, emailInvitation);
        mvc.perform(post(path(target) + "/enable").header("Authorization", "Bearer " + token(sysAdmin)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select user_id from request_recipients where invitation_id=?", Long.class, studentInvitation)).isNull();
        read(actor(UserRole.ORG_VIEWER), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.requests[?(@.recipientStatus == 'SKIPPED_INELIGIBLE')].id").value(pub("requests", request).toString()))
                .andExpect(jsonPath("$.requests[?(@.recipientStatus == 'QUEUED')].id").value(pub("requests", request).toString()))
                .andExpect(jsonPath("$.invitations.length()").value(2))
                .andExpect(jsonPath("$.invitations[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.invitations[1].status").value("ACCEPTED"));
    }

    @Test
    void domainReleaseAndElapsedRenewalAreExplainedWithoutChangingRawActiveStatus() throws Exception {
        member(workspace, target, "MEMBER");
        long released = jdbc.queryForObject("""
                insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at)
                values(?,?,'EXTERNAL',?,'pusan.dev','ACTIVE',now()+interval '30 days') returning id
                """, Long.class, workspace, org, "release-" + UUID.randomUUID() + ".pusan.dev");
        jdbc.update("insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role) values('DOMAIN',?,'USER',?,'OWNER')", released, target.getId());
        mvc.perform(delete("/api/v1/dns-domains/" + pub("domains", released))
                .header("Authorization", "Bearer " + token(target))).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select status::text from domains where id=?", String.class, released)).isEqualTo("ACTIVE");
        mvc.perform(get(path(target) + "/support/access").param("type", "DOMAIN").param("resourceId", pub("domains", released).toString())
                .header("Authorization", "Bearer " + token(sysAdmin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reasons[0]").value("RESOURCE_ENDED"));
        long overdue = jdbc.queryForObject("""
                insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at)
                values(?,?,'EXTERNAL',?,'pusan.dev','ACTIVE',now()-interval '1 minute') returning id
                """, Long.class, workspace, org, "overdue-" + UUID.randomUUID() + ".pusan.dev");
        jdbc.update("insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role) values('DOMAIN',?,'USER',?,'MEMBER')", overdue, target.getId());
        mvc.perform(get(path(target) + "/support/access").param("type", "DOMAIN").param("resourceId", pub("domains", overdue).toString())
                .header("Authorization", "Bearer " + token(sysAdmin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reasons[0]").value("RESOURCE_ENDED"));
    }

    @Test
    void closedWorkspaceKeepsScopedResourceHistoryAndRemainingGrantFindable() throws Exception {
        member(workspace, target, "MEMBER");
        long resource = vm(workspace, org);
        grant(resource, target, "EDITOR");
        jdbc.update("update vms set status='DELETED', deleted_at=now() where id=?", resource);
        mvc.perform(kr.ac.pusan.pickle.support.TerminationFixtures.delete(mvc, json, token(owner), "/api/v1/workspaces/" + pub("workspaces", workspace))).andExpect(status().isNoContent());
        read(sysAdmin, "").andExpect(status().isOk()).andExpect(jsonPath("$.memberships").isEmpty())
                .andExpect(jsonPath("$.resources[0].id").value(pub("vms", resource).toString()))
                .andExpect(jsonPath("$.resources[0].status").value("DELETED"))
                .andExpect(jsonPath("$.resources[0].canRevoke").value(true));
        diagnose(actor(UserRole.ORG_VIEWER), resource).andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons[0]").value("RESOURCE_ENDED"));
    }

    @Test
    void supportInstitutionFiltersIncludeUnmanagedDisabledInstitutionsAndDenyInventory() throws Exception {
        jdbc.update("update orgs set status='DISABLED' where id=?", foreignOrg);
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN)) {
            String actorToken = token(actor(role));
            mvc.perform(get("/api/v1/admin/support-orgs").header("Authorization", "Bearer " + actorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(pub("orgs", foreignOrg))).value("DISABLED"));
            for (String inventory : List.of("nodes", "gpus")) {
                mvc.perform(get("/api/v1/admin/" + inventory).header("Authorization", "Bearer " + actorToken))
                        .andExpect(status().isForbidden());
            }
        }
        mvc.perform(get("/api/v1/admin/support-orgs").header("Authorization", "Bearer " + token(target)))
                .andExpect(status().isForbidden());
    }

    private User user(UserRole role, UserStatus status) {
        User user = new User("support-" + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "지원 계정");
        user.setRole(role); user.setStatus(status); user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private User actor(UserRole role) {
        User actor = user(role, UserStatus.ACTIVE);
        if (role.isOrgTier()) { SeedFixtures.grantOrgRole(jdbc, actor.getId(), org, role); }
        return actor;
    }

    private long workspace() {
        long id = jdbc.queryForObject("insert into workspaces(kind,name) values('COURSE',?) returning id", Long.class, "지원 수업 " + UUID.randomUUID());
        member(id, owner, "OWNER");
        return id;
    }

    private void member(long ws, User user, String role) {
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,?::workspace_member_role)", ws, user.getId(), role);
    }

    private long request(long ws, long orgId) {
        return RequestFixtures.insertVmRequest(jdbc, ws, orgId, owner.getId(), "지원 신청", null);
    }

    private long vm(long ws, long orgId) { return vm(ws, orgId, request(ws, orgId)); }

    private long vm(long ws, long orgId, long request) {
        long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        String name = "support-" + UUID.randomUUID();
        return jdbc.queryForObject("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                values(?,?,?,?,?,?,?,2,2048,10,'RUNNING') returning id
                """, Long.class, node, ws, orgId, request, name, name, image);
    }

    private long domain(long ws, long orgId) {
        return jdbc.queryForObject("insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at) values(?,?,'EXTERNAL',?,'pusan.dev','ACTIVE',now()+interval '30 days') returning id", Long.class, ws, orgId, "support-" + UUID.randomUUID() + ".pusan.dev");
    }

    private void grant(long resource, User user, String role) {
        jdbc.update("insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role) values('VM',?,'USER',?,?::resource_role)", resource, user.getId(), role);
    }

    private long invitation(String studentNo, String email) {
        return jdbc.queryForObject("insert into workspace_invitations(workspace_id,invitee_student_no,invitee_email,role,invited_by) values(?,?,?,'MEMBER',?) returning id", Long.class, workspace, studentNo, email, owner.getId());
    }

    private void recipient(long request, long invitation) {
        jdbc.update("insert into request_recipients(public_id,request_id,invitation_id,status) values(?,?,?,'PENDING_JOIN')", UUID.randomUUID(), request, invitation);
    }

    private ResultActions read(User actor, String suffix) throws Exception {
        return mvc.perform(get(path(target) + "/support" + suffix).header("Authorization", "Bearer " + token(actor)));
    }

    private ResultActions diagnose(User actor, long vm) throws Exception {
        return mvc.perform(get(path(target) + "/support/access").param("type", "VM").param("resourceId", pub("vms", vm).toString())
                .header("Authorization", "Bearer " + token(actor)));
    }

    private ResultActions preview(User actor, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path(target) + "/profile-impact").header("Authorization", "Bearer " + token(actor))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private String token(User user) { return tokens.createAccessToken(user); }
    private String path(User user) { return "/api/v1/admin/users/" + user.getPublicId(); }
    private UUID pub(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
}
