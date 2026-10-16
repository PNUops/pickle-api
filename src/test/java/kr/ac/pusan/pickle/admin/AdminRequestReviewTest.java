package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Request review reads follow exact associations, current scope, and actual row states. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminRequestReviewTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private JwtService tokens;
    @Autowired private ObjectMapper json;

    private User applicant;
    private User sys;
    private long org;
    private long otherOrg;
    private long workspace;
    private long image;
    private long node;

    @BeforeEach
    void setUp() {
        applicant = user(UserRole.USER);
        sys = users.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        org = SeedFixtures.seedOrgId(jdbc);
        otherOrg = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "검토 기관 " + UUID.randomUUID());
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('COURSE',?) returning id", Long.class, "검토 수업 " + UUID.randomUUID());
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, applicant.getId());
        image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        node = jdbc.queryForObject("select node_id from os_images where id=?", Long.class, image);
    }

    @Test
    void referenceLinksFollowActualDetailGuardsWithoutImpersonation() throws Exception {
        long current = request(org, "현재 신청");
        long own = vm(request(org, "참고 자원"), org, "RUNNING");
        long foreign = vm(request(otherOrg, "타 기관 참고"), otherOrg, "RUNNING");
        context(actor(UserRole.ORG_VIEWER), current).andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(pub("orgs", org).toString()))
                .andExpect(jsonPath("$.vm.applicantResources.activeVms[?(@.id == '%s')].canReadDetail".formatted(pub("vms", own))).value(true))
                .andExpect(jsonPath("$.vm.applicantResources.activeVms[?(@.id == '%s')].canReadDetail".formatted(pub("vms", foreign))).value(true))
                .andExpect(jsonPath("$.vm.applicantResources.activeVms[?(@.id == '%s')].orgId".formatted(pub("vms", foreign))).value(pub("orgs", otherOrg).toString()));
        context(actor(UserRole.SYS_VIEWER), current).andExpect(status().isOk())
                .andExpect(jsonPath("$.vm.applicantResources.activeVms[?(@.id == '%s')].canReadDetail".formatted(pub("vms", foreign))).value(true));
        User other = actor(UserRole.ORG_ADMIN);
        context(other, request(otherOrg, "범위 밖")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from resource_access_grants where resource_id=? and resource_type='VM'", Long.class, foreign)).isZero();
    }

    @Test
    void systemApplicantMetadataDoesNotOpenTheWithheldUserDetail() throws Exception {
        long current = request(org, "시스템 신청자");
        jdbc.update("update requests set requester_id=? where id=?", sys.getId(), current);
        context(actor(UserRole.ORG_ADMIN), current).andExpect(status().isOk())
                .andExpect(jsonPath("$.applicant.canReadDetail").value(false));
    }

    @Test
    void departedRecipientUserLinksUseCurrentDirectoryGuardsAndNeverPendingIdentityInference() throws Exception {
        long current = request(org, "떠난 실제 대상자");
        User departed = user(UserRole.USER);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER'),(?,?,'MEMBER')",
                workspace, departed.getId(), workspace, sys.getId());
        jdbc.update("insert into request_recipients(public_id,request_id,user_id,status) values(?,?,?,'QUEUED'),(?,?,?,'QUEUED')",
                UUID.randomUUID(), current, departed.getId(), UUID.randomUUID(), current, sys.getId());
        jdbc.update("delete from workspace_members where workspace_id=? and user_id in (?,?)", workspace, departed.getId(), sys.getId());
        departed.setStatus(UserStatus.DISABLED); users.saveAndFlush(departed);
        User pending = user(UserRole.USER);
        String studentNo = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        pending.setProfile(UserPosition.STUDENT_UNDERGRAD, studentNo, null, null); users.saveAndFlush(pending);
        long invitation = jdbc.queryForObject("insert into workspace_invitations(workspace_id,invitee_student_no,role,invited_by) values(?,?,'MEMBER',?) returning id",
                Long.class, workspace, studentNo, applicant.getId());
        jdbc.update("insert into request_recipients(public_id,request_id,invitation_id,status) values(?,?,?,'PENDING_JOIN')", UUID.randomUUID(), current, invitation);
        long recipientsBefore = count("request_recipients");
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN,
                UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)) {
            User viewer = actor(role);
            context(viewer, current).andExpect(status().isOk())
                    .andExpect(jsonPath("$.recipientUserReadFlags['%s']".formatted(departed.getPublicId())).value(true))
                    .andExpect(jsonPath("$.recipientUserReadFlags['%s']".formatted(sys.getPublicId())).value(!role.isOrgTier()))
                    .andExpect(jsonPath("$.recipientUserReadFlags['%s']".formatted(pending.getPublicId())).doesNotExist());
            mvc.perform(get("/api/v1/admin/users/" + departed.getPublicId()).header("Authorization", "Bearer " + token(viewer)))
                    .andExpect(status().isOk());
            if (role.isOrgTier()) {
                mvc.perform(get("/api/v1/admin/users/" + sys.getPublicId()).header("Authorization", "Bearer " + token(viewer)))
                        .andExpect(status().isNotFound());
            }
        }
        assertThat(count("request_recipients")).isEqualTo(recipientsBefore);
        assertThat(jdbc.queryForObject("select status from request_recipients where request_id=? and user_id=?", String.class, current, departed.getId()))
                .isEqualTo("QUEUED");
    }

    @Test
    void registeredRecipientsKeepActualProvisioningStatesAndResourceIds() throws Exception {
        long current = request(org, "대상별 결과");
        long creating = vm(current, org, "CREATING");
        UUID recipient = UUID.randomUUID();
        jdbc.update("insert into request_recipients(public_id,request_id,user_id,status,resource_id) values(?,?,?,'CREATED',?)",
                recipient, current, applicant.getId(), creating);
        context(sys, current).andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedResources[0].id").value(pub("vms", creating).toString()))
                .andExpect(jsonPath("$.generatedResources[0].recipientId").value(recipient.toString()))
                .andExpect(jsonPath("$.generatedResources[0].nodeId").value(pub("nodes", node).toString()))
                .andExpect(jsonPath("$.generatedResources[0].state").value("CREATING"));
        jdbc.update("update vms set status='ERROR' where id=?", creating);
        context(sys, current).andExpect(status().isOk()).andExpect(jsonPath("$.generatedResources[0].state").value("ERROR"));
        jdbc.update("update vms set status='RUNNING' where id=?", creating);
        context(sys, current).andExpect(status().isOk()).andExpect(jsonPath("$.generatedResources[0].state").value("RUNNING"));
        assertThat(jdbc.queryForObject("select status from request_recipients where public_id=?", String.class, recipient)).isEqualTo("CREATED");
    }

    @Test
    void singleAndOtherResourceFamiliesUseTheirOwnPersistedAssociations() throws Exception {
        long single = request(org, "단일 결과");
        long created = vm(single, org, "CREATING");
        context(sys, single).andExpect(status().isOk()).andExpect(jsonPath("$.generatedResources[0].id").value(pub("vms", created).toString()))
                .andExpect(jsonPath("$.generatedResources[0].recipientId").isEmpty());
        long keyRequest = RequestFixtures.insertLlmKeyRequest(jdbc, workspace, org, applicant.getId(), "기간 지난 키");
        long key = jdbc.queryForObject("insert into llm_api_keys(workspace_id,org_id,request_id,name,created_by,expires_at) values(?,?,?,'등록 키',?,now()-interval '1 minute') returning id",
                Long.class, workspace, org, keyRequest, applicant.getId());
        context(sys, keyRequest).andExpect(status().isOk()).andExpect(jsonPath("$.generatedResources[0].id").value(pub("llm_api_keys", key).toString()))
                .andExpect(jsonPath("$.generatedResources[0].state").value("EXPIRED"));
        long gpuRequest = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('GPU',?,?,?,'GPU 결과','GPU 결과') returning id",
                Long.class, workspace, org, applicant.getId());
        jdbc.update("insert into gpu_request_details(request_id,lease_hours) values(?,1)", gpuRequest);
        long allocation = jdbc.queryForObject("insert into gpu_allocations(request_id,workspace_id,org_id,name,granted_lease_hours) values(?,?,?,'대기 GPU',1) returning id", Long.class, gpuRequest, workspace, org);
        context(sys, gpuRequest).andExpect(status().isOk()).andExpect(jsonPath("$.generatedResources[0].id").value(pub("gpu_allocations", allocation).toString()))
                .andExpect(jsonPath("$.generatedResources[0].state").value("QUEUED"));
    }

    @Test
    void exactDomainIssuanceKeepsItsOriginalIdentityWhenTheNameIsReissued() throws Exception {
        long current = domainRequest();
        long original = jdbc.queryForObject("select id from domains where request_id=?", Long.class, current);
        String name = jdbc.queryForObject("select fqdn from domains where id=?", String.class, original);
        jdbc.update("update domains set status='REMOVED' where id=?", original);
        long replacement = jdbc.queryForObject("insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at) values(?,?,'EXTERNAL',?,'pusan.dev','ACTIVE',now()+interval '30 days') returning id", Long.class, workspace, org, name);
        context(sys, current).andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedResources.length()").value(1))
                .andExpect(jsonPath("$.generatedResources[0].id").value(pub("domains", original).toString()))
                .andExpect(jsonPath("$.generatedResources[0].state").value("REMOVED"))
                .andExpect(jsonPath("$.generatedResources[0].canReadDetail").value(false));
        assertThat(pub("domains", original)).isNotEqualTo(pub("domains", replacement));
        mvc.perform(get("/api/v1/admin/domains/" + pub("domains", original) + "/records")
                .header("Authorization", "Bearer " + token(sys))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/domains").param("status", "REMOVED").param("workspaceId", pub("workspaces", workspace).toString())
                .header("Authorization", "Bearer " + token(sys)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(pub("domains", original))).isNotEmpty());
    }

    @Test
    void legacyDomainNamesHaveNoInferredResourceAssociation() throws Exception {
        long current = domainRequest();
        jdbc.update("update domains set request_id=null where request_id=?", current);
        context(sys, current).andExpect(status().isOk()).andExpect(jsonPath("$.generatedResources").isEmpty());
        assertThat(jdbc.queryForObject("select granted_fqdn from domain_request_details where request_id=?", String.class, current))
                .isNotBlank();
    }

    @Test
    void domainStateObservationsPreserveRawStateAndActualReleaseAndRenewalDates() throws Exception {
        long current = domainRequest();
        long issued = jdbc.queryForObject("select id from domains where request_id=?", Long.class, current);
        Instant released = Instant.parse("2026-10-04T01:00:00Z");
        Instant renewal = Instant.parse("2026-10-03T01:00:00Z");
        jdbc.update("update domains set released_at=?,renew_due_at=? where id=?",
                java.time.OffsetDateTime.ofInstant(released, java.time.ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(renewal, java.time.ZoneOffset.UTC), issued);
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN,
                UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)) {
            context(actor(role), current).andExpect(status().isOk())
                    .andExpect(jsonPath("$.generatedResources[0].state").value("ACTIVE"))
                    .andExpect(jsonPath("$.generatedResources[0].canReadDetail").value(true))
                    .andExpect(jsonPath("$.generatedResources[0].releasedAt").value(released.toString()))
                    .andExpect(jsonPath("$.generatedResources[0].renewDueAt").value(renewal.toString()))
                    .andExpect(jsonPath("$.generatedResources[0].reservedUntil").isNotEmpty());
        }
        User outsider = user(UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbc, outsider.getId(), otherOrg, UserRole.ORG_ADMIN);
        context(outsider, current).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/domains/" + pub("domains", issued) + "/records")
                .header("Authorization", "Bearer " + token(actor(UserRole.ORG_VIEWER)))).andExpect(status().isOk());
        long domainCount = count("domains"), grantCount = count("resource_access_grants"), jobCount = count("jobrunr_jobs");
        context(sys, current).andExpect(status().isOk());
        assertThat(count("domains")).isEqualTo(domainCount);
        assertThat(count("resource_access_grants")).isEqualTo(grantCount);
        assertThat(count("jobrunr_jobs")).isEqualTo(jobCount);
        jdbc.update("update domains set org_id=? where id=?", otherOrg, issued);
        context(actor(UserRole.ORG_VIEWER), current).andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedResources").isEmpty());
    }

    @Test
    void exactDomainReadsMatchListingScopeAndStateWithoutWritingOrPolling() throws Exception {
        long current = domainRequest();
        long issued = jdbc.queryForObject("select id from domains where request_id=?", Long.class, current);
        UUID domainId = pub("domains", issued);
        jdbc.update("update domains set released_at=now(),renew_due_at=now()-interval '1 day' where id=?", issued);
        long domainsBefore = count("domains"), recordsBefore = count("domain_records"), auditsBefore = count("audit_logs"), jobsBefore = count("jobrunr_jobs");
        Instant updatedBefore = jdbc.queryForObject("select updated_at from domains where id=?", java.time.OffsetDateTime.class, issued).toInstant();
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN,
                UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)) {
            User viewer = actor(role);
            String one = mvc.perform(get("/api/v1/admin/domains/" + domainId).header("Authorization", "Bearer " + token(viewer)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(domainId.toString()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.releasedAt").isNotEmpty()).andExpect(jsonPath("$.renewDueAt").isNotEmpty())
                    .andExpect(jsonPath("$.reservedUntil").isNotEmpty()).andReturn().getResponse().getContentAsString();
            String listing = mvc.perform(get("/api/v1/admin/domains").param("workspaceId", pub("workspaces", workspace).toString())
                    .header("Authorization", "Bearer " + token(viewer))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(one)).isEqualTo(json.readTree(listing).path("content").get(0));
        }
        User multiOrg = actor(UserRole.ORG_VIEWER);
        SeedFixtures.grantOrgRole(jdbc, multiOrg.getId(), otherOrg, UserRole.ORG_MANAGER);
        mvc.perform(get("/api/v1/admin/domains/" + domainId).header("Authorization", "Bearer " + token(multiOrg)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/domains/" + domainId).header("Authorization", "Bearer " + token(applicant)))
                .andExpect(status().isForbidden());
        User outsider = user(UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbc, outsider.getId(), otherOrg, UserRole.ORG_ADMIN);
        for (User denied : List.of(outsider, user(UserRole.ORG_VIEWER))) {
            mvc.perform(get("/api/v1/admin/domains/" + domainId).header("Authorization", "Bearer " + token(denied)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/admin/domains/" + UUID.randomUUID()).header("Authorization", "Bearer " + token(sys)))
                .andExpect(status().isNotFound());
        assertThat(count("domains")).isEqualTo(domainsBefore);
        assertThat(count("domain_records")).isEqualTo(recordsBefore);
        assertThat(count("audit_logs")).isEqualTo(auditsBefore);
        assertThat(count("jobrunr_jobs")).isEqualTo(jobsBefore);
        assertThat(jdbc.queryForObject("select updated_at from domains where id=?", java.time.OffsetDateTime.class, issued).toInstant())
                .isEqualTo(updatedBefore);
        jdbc.update("update domains set org_id=null where id=?", issued);
        mvc.perform(get("/api/v1/admin/domains/" + domainId).header("Authorization", "Bearer " + token(multiOrg)))
                .andExpect(status().isNotFound());
        jdbc.update("update domains set org_id=?,status='REMOVED' where id=?", org, issued);
        mvc.perform(get("/api/v1/admin/domains/" + domainId).header("Authorization", "Bearer " + token(sys)))
                .andExpect(status().isNotFound());
    }

    @Test
    void exactDomainReadsRefuseUnknownRequiredIdentityForEveryAdministratorTier() throws Exception {
        long current = domainRequest();
        long issued = jdbc.queryForObject("select id from domains where request_id=?", Long.class, current);
        UUID id = pub("domains", issued);
        List<User> viewers = List.of(actor(UserRole.ORG_VIEWER), actor(UserRole.ORG_MANAGER), actor(UserRole.ORG_ADMIN),
                actor(UserRole.SYS_VIEWER), actor(UserRole.SYS_MANAGER), actor(UserRole.SYS_ADMIN));
        jdbc.update("update domains set org_id=null where id=?", issued);
        for (User viewer : viewers) {
            mvc.perform(get("/api/v1/admin/domains/" + id).header("Authorization", "Bearer " + token(viewer)))
                    .andExpect(status().isNotFound());
        }
        jdbc.update("update domains set org_id=?,workspace_id=null where id=?", org, issued);
        for (User viewer : viewers) {
            mvc.perform(get("/api/v1/admin/domains/" + id).header("Authorization", "Bearer " + token(viewer)))
                    .andExpect(status().isNotFound());
        }
        jdbc.update("update domains set workspace_id=? where id=?", workspace, issued);
        for (User viewer : viewers) {
            mvc.perform(get("/api/v1/admin/domains/" + id).header("Authorization", "Bearer " + token(viewer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orgId").value(pub("orgs", org).toString()))
                    .andExpect(jsonPath("$.orgName").isNotEmpty())
                    .andExpect(jsonPath("$.workspaceId").value(pub("workspaces", workspace).toString()))
                    .andExpect(jsonPath("$.workspaceName").isNotEmpty());
        }
    }

    @Test
    void vmBoundDomainReadsKeepValidIdentityAndDoNotInferMissingOwnershipFromTheVm() throws Exception {
        long vm = vm(request(org, "이름이 쓰는 VM"), org, "RUNNING");
        long domain = jdbc.queryForObject("""
                insert into domains(workspace_id,org_id,vm_id,kind,fqdn,root_domain,status)
                values(?,?,?,'PLATFORM',?,'pusan.dev','ACTIVE') returning id
                """, Long.class, workspace, org, vm, "bound-" + UUID.randomUUID() + ".pusan.dev");
        for (UserRole role : List.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER, UserRole.ORG_ADMIN,
                UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)) {
            mvc.perform(get("/api/v1/admin/domains/" + pub("domains", domain))
                    .header("Authorization", "Bearer " + token(actor(role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vmId").value(pub("vms", vm).toString()))
                    .andExpect(jsonPath("$.orgId").value(pub("orgs", org).toString()))
                    .andExpect(jsonPath("$.workspaceId").value(pub("workspaces", workspace).toString()));
        }
        jdbc.update("update domains set org_id=null,workspace_id=null where id=?", domain);
        for (UserRole role : List.of(UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)) {
            mvc.perform(get("/api/v1/admin/domains/" + pub("domains", domain))
                    .header("Authorization", "Bearer " + token(actor(role))))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void placementIsPurePurposeBoundAndDoesNotOpenGeneralInventory() throws Exception {
        var body = Map.<String,Object>of("orgId", pub("orgs", org), "imageId", pub("os_images", image), "vcpu", 1, "memoryMb", 512, "diskGb", 10);
        long vmsBefore = count("vms"), auditsBefore = count("audit_logs"), jobsBefore = count("jobrunr_jobs");
        for (UserRole role : List.of(UserRole.ORG_MANAGER, UserRole.ORG_ADMIN, UserRole.SYS_ADMIN)) {
            User actor = actor(role);
            preview(actor, body).andExpect(status().isOk()).andExpect(jsonPath("$.observedAt").isNotEmpty())
                    .andExpect(jsonPath("$.inputImageId").value(pub("os_images", image).toString()))
                    .andExpect(jsonPath("$.image.selectableNow").value(true))
                    .andExpect(jsonPath("$.candidates[*].nodeId").value(hasItem(pub("nodes", node).toString())));
            if (role.isOrgTier()) {
                mvc.perform(get("/api/v1/admin/nodes").header("Authorization", "Bearer " + token(actor))).andExpect(status().isForbidden());
            }
        }
        for (UserRole role : List.of(UserRole.USER, UserRole.ORG_VIEWER, UserRole.SYS_VIEWER, UserRole.SYS_MANAGER)) {
            preview(actor(role), body).andExpect(status().isForbidden());
        }
        preview(actor(UserRole.ORG_ADMIN), Map.of("orgId", pub("orgs", otherOrg), "imageId", pub("os_images", image), "vcpu", 1, "memoryMb", 512, "diskGb", 10))
                .andExpect(status().isUnprocessableContent());
        assertThat(count("vms")).isEqualTo(vmsBefore);
        assertThat(count("audit_logs")).isEqualTo(auditsBefore);
        assertThat(count("jobrunr_jobs")).isEqualTo(jobsBefore);
    }

    @Test
    void periodAndTextFiltersUseSnapshotAndRunBeforePagination() throws Exception {
        String prefix = "filter-" + UUID.randomUUID();
        LocalDate date = LocalDate.now(ClockConfig.KST).plusDays(30);
        long matching = request(org, prefix + " matching");
        jdbc.update("update requests set req_end_date=? where id=?", date, matching);
        request(org, prefix + " indefinite");
        request(otherOrg, prefix + " foreign");
        mvc.perform(get("/api/v1/admin/requests").param("q", prefix).param("requestedEndFrom", date.toString()).param("requestedEndTo", date.toString())
                .param("size", "1").header("Authorization", "Bearer " + token(actor(UserRole.ORG_VIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pub("requests", matching).toString()));
        mvc.perform(get("/api/v1/admin/requests").param("q", prefix).param("reqIndefinite", "true")
                .header("Authorization", "Bearer " + token(actor(UserRole.ORG_VIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        String hiddenNumber = "H" + UUID.randomUUID().toString().replace("-", "").substring(0,9);
        applicant.setProfile(UserPosition.STUDENT_UNDERGRAD, hiddenNumber, null, null); users.saveAndFlush(applicant);
        mvc.perform(get("/api/v1/admin/requests").param("q", hiddenNumber).header("Authorization", "Bearer " + token(sys)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void relatedUserFilterUsesAcceptedIdentityButNeverPendingStudentMatch() throws Exception {
        User target = user(UserRole.USER);
        String studentNo = "R" + UUID.randomUUID().toString().replace("-", "").substring(0,9);
        target.setProfile(UserPosition.STUDENT_UNDERGRAD, studentNo, null, null); users.saveAndFlush(target);
        long current = request(org, "관계 필터");
        long invitation = jdbc.queryForObject("insert into workspace_invitations(workspace_id,invitee_student_no,role,invited_by) values(?,?,'MEMBER',?) returning id", Long.class, workspace, studentNo, applicant.getId());
        jdbc.update("insert into request_recipients(public_id,request_id,invitation_id,status) values(?,?,?,'PENDING_JOIN')", UUID.randomUUID(), current, invitation);
        listRelated(target).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        jdbc.update("update workspace_invitations set accepted_user_id=?,accepted_at=now(),status='ACCEPTED' where id=?", target.getId(), invitation);
        jdbc.update("update request_recipients set status='SKIPPED_INELIGIBLE' where invitation_id=?", invitation);
        listRelated(target).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void compatibleLegacyReplicaReferencesUseTheSameLogicalSelectabilityAsSubmission() throws Exception {
        long replicaNode = jdbc.queryForObject("insert into nodes(name,api_host,status,cpu_threads,memory_mb,vm_bridge,storage) values(?,'https://example.invalid:8006','ACTIVE',8,8192,'vmbr0','local-lvm') returning id",
                Long.class, "replica-" + UUID.randomUUID());
        long replica = jdbc.queryForObject("""
                insert into os_images(name,display_name,os_family,os_version,ssh_username,proxmox_vmid,node_id,version,min_disk_gb,status)
                select name,display_name,os_family,os_version,ssh_username,10101,?,version,min_disk_gb,'ACTIVE'
                  from os_images where id=? returning id
                """, Long.class, replicaNode, image);
        long current = request(org, "호환 이미지 참조");
        jdbc.update("update vm_request_details set image_id=? where request_id=?", replica, current);
        context(sys, current).andExpect(status().isOk())
                .andExpect(jsonPath("$.vm.requestedImage.id").value(pub("os_images", replica).toString()))
                .andExpect(jsonPath("$.vm.requestedImage.selectableNow").value(true));
        preview(sys, Map.of("orgId", pub("orgs", org), "imageId", pub("os_images", replica), "vcpu", 1, "memoryMb", 512, "diskGb", 10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputImageId").value(pub("os_images", replica).toString()))
                .andExpect(jsonPath("$.image.id").value(pub("os_images", image).toString()))
                .andExpect(jsonPath("$.image.selectableNow").value(true));
    }

    private ResultActions listRelated(User target) throws Exception {
        return mvc.perform(get("/api/v1/admin/requests").param("relatedUserId", target.getPublicId().toString())
                .header("Authorization", "Bearer " + token(actor(UserRole.ORG_VIEWER))));
    }

    private long request(long institution, String purpose) {
        return RequestFixtures.insertVmRequest(jdbc, workspace, institution, applicant.getId(), purpose, image);
    }

    private long domainRequest() throws Exception {
        jdbc.update("insert into domain_roots(root_domain,org_id,auto_approve) values('pusan.dev',?,false) on conflict(root_domain) do update set org_id=excluded.org_id", org);
        long current = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('DOMAIN',?,?,?,'도메인 결과','도메인 결과') returning id", Long.class, workspace, org, applicant.getId());
        String label = "history-" + UUID.randomUUID().toString().substring(0, 16);
        jdbc.update("insert into domain_request_details(request_id,label,root_domain) values(?,?,'pusan.dev')", current, label);
        mvc.perform(post("/api/v1/admin/requests/" + pub("requests", current) + "/approve")
                .header("Authorization", "Bearer " + token(sys)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        return current;
    }

    private long vm(long request, long institution, String state) {
        String name = "review-" + UUID.randomUUID();
        return jdbc.queryForObject("insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status) values(?,?,?,?,?,?,?,1,512,10,?::vm_status) returning id",
                Long.class, node, workspace, institution, request, name, name, image, state);
    }

    private User user(UserRole role) {
        User user = new User("review-" + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "신청 검토 계정");
        user.setRole(role); user.setStatus(UserStatus.ACTIVE); user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private User actor(UserRole role) {
        User actor = user(role);
        if (role.isOrgTier()) { SeedFixtures.grantOrgRole(jdbc, actor.getId(), org, role); }
        return actor;
    }

    private ResultActions context(User actor, long request) throws Exception {
        return mvc.perform(get("/api/v1/admin/requests/" + pub("requests", request) + "/context")
                .header("Authorization", "Bearer " + token(actor)));
    }

    private ResultActions preview(User actor, Map<String,Object> body) throws Exception {
        return mvc.perform(post("/api/v1/admin/vm-placement/preview").header("Authorization", "Bearer " + token(actor))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    private String token(User user) { return tokens.createAccessToken(user); }
    private UUID pub(String table, long id) { return SeedFixtures.publicId(jdbc, table, id); }
}
