package kr.ac.pusan.pickle.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.OsImageRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.provisioning.VmCloneReservationService;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.InvitationClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Requests that make a resource for each of many people: who may name
 * recipients, submit-and-approve in one step, what approval leaves behind
 * (nothing created, every recipient placed), the materializer that creates
 * one resource per recipient afterwards, and the invitee who joins later.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class RequestRecipientsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private OsImageRepository imageRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RequestRecipientMaterializer materializer;

    @MockitoSpyBean
    private VmCloneReservationService cloneReservations;

    @Autowired
    private InvitationClaimService invitationClaimService;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Autowired
    private kr.ac.pusan.pickle.llm.openrouter.OpenRouterManagementCredentialCipher managementCipher;

    private Org org;
    private OsImage image;
    private User owner;
    private String ownerToken;
    private User orgAdmin;
    private String orgAdminToken;
    private int sequence;

    @BeforeEach
    void setUp() {
        org = orgRepository.findFirstByNameOrderByIdAsc(SeedFixtures.ORG_NAME).orElseThrow();
        image = imageRepository.findAll().stream()
                .filter(t -> t.getName().equals("ubuntu-24.04") && t.getStatus() == CatalogStatus.ACTIVE)
                .findFirst().orElseThrow();
        owner = ensureUser("bulk.owner@pusan.ac.kr", "대상자신청자");
        ownerToken = jwtService.createAccessToken(owner);
        orgAdmin = userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow();
        orgAdminToken = jwtService.createAccessToken(orgAdmin);
        // Each test starts with nothing of this path in flight: the background
        // server is off here, so a VM made by an earlier test would stay
        // CREATING for ever and hold a slot of the concurrency limit, and a row
        // an earlier test left queued would be created by this test's run.
        jdbcTemplate.update("update vms set status = 'RUNNING' where status = 'CREATING'");
        jdbcTemplate.update("update request_recipients set status = 'CANCELED' where status = 'QUEUED'");
        jdbcTemplate.update("delete from settings where key = 'bulk_provision_concurrency'");
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'workspace_invite%'");
    }

    // ------------------------------------------------------------ submission

    @Test
    void recipientsAreValidatedEntryByEntry() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User member = addMember(workspace, "valid");
        User outsider = ensureUser(email("outsider"), "구성원아님");
        UUID invitation = invite(workspace, email("pending"));
        UUID canceledInvitation = invite(workspace, email("canceled"));
        jdbcTemplate.update("""
                update workspace_invitations set status = 'CANCELED', canceled_by = ?, canceled_at = now()
                 where public_id = ?
                """, owner.getId(), canceledInvitation);

        postJson("/api/v1/requests", ownerToken, vmBody(workspace, List.of(
                Map.of("userId", outsider.getPublicId()),
                Map.of("invitationId", canceledInvitation),
                Map.of("userId", member.getPublicId()),
                Map.of("userId", member.getPublicId()),
                Map.of("userId", member.getPublicId(), "invitationId", invitation),
                Map.of())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients[0]')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients[1]')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients[2]')]").doesNotExist())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients[3]')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients[4]')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients[5]')]").exists());

        JsonNode created = created(postJson("/api/v1/requests", ownerToken, vmBody(workspace, List.of(
                Map.of("userId", member.getPublicId()), Map.of("invitationId", invitation)))));
        assertThat(created.get("status").asString()).isEqualTo("SUBMITTED");
        JsonNode recipients = created.get("recipients");
        assertThat(recipients).hasSize(2);
        assertThat(recipients.get(0).get("userId").asString()).isEqualTo(member.getPublicId().toString());
        assertThat(recipients.get(0).get("name").asString()).isEqualTo(member.getName());
        assertThat(recipients.get(0).get("status").asString()).isEqualTo("QUEUED");
        assertThat(recipients.get(1).get("status").asString()).isEqualTo("PENDING_JOIN");
        // The requester sees where the invitation went; another member does not.
        assertThat(recipients.get(1).get("invitee").asString()).isEqualTo(email("pending"));
        String memberToken = jwtService.createAccessToken(member);
        mockMvc.perform(get("/api/v1/requests/" + created.get("id").asString())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[1].invitee").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/requests/" + created.get("id").asString())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[1].invitee").value(email("pending")));
    }

    @Test
    void onlyOwnersAndApproversNameRecipients() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User member = addMember(workspace, "plain");
        User other = addMember(workspace, "other");
        String memberToken = jwtService.createAccessToken(member);

        postJson("/api/v1/requests", memberToken, vmBody(workspace, List.of(
                Map.of("userId", other.getPublicId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REQUEST_RECIPIENTS_FORBIDDEN"));
        // Without recipients the same member asks as before.
        postJson("/api/v1/requests", memberToken, vmBody(workspace, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipients.length()").value(0));
    }

    @Test
    void recipientsAreForVmsAndKeysOnlyAndVmsTakeNoChosenHostname() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User member = addMember(workspace, "kinds");
        List<Map<String, Object>> recipients = List.of(Map.of("userId", member.getPublicId()));

        Map<String, Object> gpu = common(workspace, "GPU");
        gpu.put("recipients", recipients);
        postJson("/api/v1/requests", ownerToken, gpu)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients')]").exists());

        Map<String, Object> domain = common(workspace, "DOMAIN");
        domain.put("recipients", recipients);
        domain.put("domain", Map.of("label", "bulk-kinds", "rootDomain", "example.invalid"));
        mockMvc.perform(post("/api/v1/requests").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(domain)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 422));

        Map<String, Object> vm = vmBody(workspace, recipients);
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) vm.get("vm");
        spec.put("desiredSlug", "bulk-chosen-name");
        postJson("/api/v1/requests", ownerToken, vm)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'vm.desiredSlug')]").exists());
    }

    // ------------------------------------------------------ submit + approve

    @Test
    void anApproverOutsideTheWorkspaceSubmitsAndApprovesInOneStep() throws Exception {
        UUID workspace = linkedWorkspace();
        User member = addMember(workspace, "one-step");
        Map<String, Object> body = vmBody(workspace, List.of(Map.of("userId", member.getPublicId())));
        body.put("approval", vmApproval(2048));

        JsonNode created = created(postJson("/api/v1/requests", orgAdminToken, body));
        assertThat(created.get("status").asString()).isEqualTo("APPROVED");
        assertThat(created.get("requesterId").asString()).isEqualTo(orgAdmin.getPublicId().toString());
        assertThat(created.get("review").get("reviewerId").asString())
                .isEqualTo(orgAdmin.getPublicId().toString());
        assertThat(created.get("recipients").get(0).get("status").asString()).isEqualTo("QUEUED");
        long requestId = SeedFixtures.internalId(jdbcTemplate, "requests",
                UUID.fromString(created.get("id").asString()));
        assertThat(vmCount(requestId)).isZero();

        for (String action : List.of("request.create", "request.approve")) {
            Map<String, Object> audit = jdbcTemplate.queryForMap("""
                    select actor_id, detail ->> 'submittedByReviewer' as by_reviewer,
                           detail ->> 'automatic' as automatic
                      from audit_logs where action = ? and target_id = ?
                    """, action, created.get("id").asString());
            assertThat(audit.get("actor_id")).isEqualTo(orgAdmin.getId());
            assertThat(audit.get("by_reviewer")).isEqualTo("true");
            assertThat(audit.get("automatic")).isNull();
        }
    }

    @Test
    void aRefusedApprovalRollsTheSubmissionBack() throws Exception {
        UUID workspace = linkedWorkspace();
        User member = addMember(workspace, "rollback");
        Long before = jdbcTemplate.queryForObject("select count(*) from requests", Long.class);
        Map<String, Object> body = vmBody(workspace, List.of(Map.of("userId", member.getPublicId())));
        Map<String, Object> approval = vmApproval(2048);
        @SuppressWarnings("unchecked")
        Map<String, Object> vm = (Map<String, Object>) approval.get("vm");
        vm.put("grantedImageId", SeedFixtures.UNKNOWN_ID);
        body.put("approval", approval);

        postJson("/api/v1/requests", orgAdminToken, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'approval.vm.grantedImageId')]").exists());
        assertThat(jdbcTemplate.queryForObject("select count(*) from requests", Long.class)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from request_recipients where user_id = ?", Long.class, member.getId()))
                .isZero();
    }

    @Test
    void aNonApproverCannotApproveOnSubmission() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        Map<String, Object> body = vmBody(workspace, null);
        body.put("approval", vmApproval(2048));
        postJson("/api/v1/requests", ownerToken, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        // And an approver outside the workspace cannot file without deciding.
        postJson("/api/v1/requests", orgAdminToken, vmBody(workspace, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBERSHIP_REQUIRED"));
    }

    @Test
    void anApproverFilesIntoAWorkspaceTheOrganisationHasNeverUsed() throws Exception {
        // A course workspace a professor has just created has no request or VM
        // in any organisation yet. An org approver reaches it all the same
        // (operator decision, 2026-09-28), which is the case bulk requests exist for.
        UUID fresh = createWorkspace(ownerToken);
        Map<String, Object> body = vmBody(fresh, List.of(Map.of("userId", owner.getPublicId())));
        body.put("approval", vmApproval(2048));
        postJson("/api/v1/requests", orgAdminToken, body)
                .andExpect(status().isCreated());
    }

    @Test
    void approversListAWorkspacesPendingInvitationsAndNameThem() throws Exception {
        UUID workspace = linkedWorkspace();
        String address = email("admin-listed");
        UUID invitation = invite(workspace, address);
        String uri = "/api/v1/admin/workspaces/" + workspace + "/invitations";

        mockMvc.perform(get(uri).header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].email".formatted(invitation)).value(address));
        User sysAdmin = userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        mockMvc.perform(get(uri).header("Authorization", "Bearer " + jwtService.createAccessToken(sysAdmin)))
                .andExpect(status().isOk());
        mockMvc.perform(get(uri).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
        // Every admin role reads every workspace's invitations (2026-09-28).
        mockMvc.perform(get(uri).header("Authorization", "Bearer " + otherOrgAdminToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/workspaces/" + createWorkspace(ownerToken) + "/invitations")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/workspaces/" + SeedFixtures.UNKNOWN_ID + "/invitations")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound());

        // What the list returned is what the approver names.
        Map<String, Object> body = vmBody(workspace, List.of(Map.of("invitationId", invitation)));
        body.put("approval", vmApproval(2048));
        JsonNode created = created(postJson("/api/v1/requests", orgAdminToken, body));
        assertThat(statuses(created)).containsExactly("PENDING_JOIN");
        assertThat(created.get("recipients").get(0).get("invitee").asString()).isEqualTo(address);
    }

    // -------------------------------------------------------------- approval

    @Test
    void approvalCreatesNothingAndPlacesEveryRecipient() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User stays = addMember(workspace, "stays");
        User leaves = addMember(workspace, "leaves");
        UUID invitation = invite(workspace, email("later"));
        JsonNode submitted = created(postJson("/api/v1/requests", ownerToken, vmBody(workspace, List.of(
                Map.of("userId", stays.getPublicId()), Map.of("userId", leaves.getPublicId()),
                Map.of("invitationId", invitation)))));
        jdbcTemplate.update("delete from workspace_members where user_id = ?", leaves.getId());
        // The requester need not stay: nothing will be theirs.
        jdbcTemplate.update("delete from workspace_members where user_id = ? and workspace_id = ?",
                owner.getId(), SeedFixtures.internalId(jdbcTemplate, "workspaces", workspace));

        String id = submitted.get("id").asString();
        JsonNode approved = objectMapper.readTree(postJson("/api/v1/admin/requests/" + id + "/approve",
                orgAdminToken, vmApproval(2048))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(statuses(approved)).containsExactly("QUEUED", "SKIPPED_INELIGIBLE", "PENDING_JOIN");
        assertThat(approved.get("recipients").get(1).get("reason").asString()).isNotBlank();
        long requestId = SeedFixtures.internalId(jdbcTemplate, "requests", UUID.fromString(id));
        assertThat(vmCount(requestId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from jobrunr_jobs where jobsignature like '%RequestRecipientMaterializer.run(%'",
                Long.class)).isPositive();

        materializer.run();
        assertThat(vmCount(requestId)).isEqualTo(1);
        long vmId = jdbcTemplate.queryForObject("select id from vms where request_id = ?", Long.class, requestId);
        assertThat(ownerOf("VM", vmId)).isEqualTo(stays.getId());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, resource_id from request_recipients where request_id = ? and user_id = ?",
                requestId, stays.getId());
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("resource_id")).isEqualTo(vmId);
        mockMvc.perform(get("/api/v1/admin/requests/" + id).header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(jsonPath("$.recipients[0].resourceId").value(
                        SeedFixtures.publicId(jdbcTemplate, "vms", vmId).toString()));
    }

    @Test
    void cancelingASubmittedRequestCancelsItsRecipients() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User member = addMember(workspace, "cancel");
        JsonNode submitted = created(postJson("/api/v1/requests", ownerToken, vmBody(workspace, List.of(
                Map.of("userId", member.getPublicId()), Map.of("invitationId", invite(workspace, email("c")))))));
        JsonNode canceled = objectMapper.readTree(postJson("/api/v1/requests/" + submitted.get("id").asString()
                + "/cancel", ownerToken, Map.of()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(statuses(canceled)).containsExactly("CANCELED", "CANCELED");
    }

    // ---------------------------------------------------------- materializer

    @Test
    void theConcurrencySettingLimitsVmsInCreation() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User first = addMember(workspace, "conc-1");
        User second = addMember(workspace, "conc-2");
        long requestId = approvedVmRequest(workspace, List.of(first, second), 2048);
        jdbcTemplate.update("""
                insert into settings (key, value, description) values ('bulk_provision_concurrency', '1'::jsonb, 'test')
                """);

        materializer.run();
        assertThat(vmCount(requestId)).isEqualTo(1);
        materializer.run();
        assertThat(vmCount(requestId)).isEqualTo(1);
        // Held back by the limit, not failed.
        assertThat(recipientStatus(requestId, second.getId())).isEqualTo("QUEUED");

        // Once the first leaves creation, the next one starts.
        jdbcTemplate.update("update vms set status = 'RUNNING' where request_id = ?", requestId);
        materializer.run();
        assertThat(vmCount(requestId)).isEqualTo(2);
        assertThat(ownerOf("VM", jdbcTemplate.queryForObject(
                "select resource_id from request_recipients where request_id = ? and user_id = ?",
                Long.class, requestId, second.getId()))).isEqualTo(second.getId());
    }

    /**
     * Also pins the intended spill onto a GPU node: GPU nodes rank last, but
     * one is used as soon as the node ahead of it is at the limit.
     */
    @Test
    void theConcurrencyLimitAppliesToEachNodeAndSpillsOntoAGpuNode() throws Exception {
        long[] nodes = twoNodeImage();
        try {
            UUID workspace = createWorkspace(ownerToken);
            List<User> recipients = List.of(addMember(workspace, "node-1"), addMember(workspace, "node-2"),
                    addMember(workspace, "node-3"));
            long requestId = approvedVmRequest(workspace, recipients, 1024);
            jdbcTemplate.update("""
                    insert into settings (key, value, description) values ('bulk_provision_concurrency', '1'::jsonb, 'test')
                    """);

            // The CPU node is preferred, so the first VM lands there; with
            // that node at the limit the second still starts, on the GPU node.
            materializer.run();
            assertThat(vmNodes(requestId)).containsExactlyInAnyOrder(nodes[0], nodes[1]);

            // Both nodes at the limit: the third recipient waits, it does not fail.
            materializer.run();
            assertThat(vmCount(requestId)).isEqualTo(2);
            Map<String, Object> waiting = jdbcTemplate.queryForMap("""
                    select status, reason from request_recipients
                     where request_id = ? and resource_id is null
                    """, requestId);
            assertThat(waiting.get("status")).isEqualTo("QUEUED");
            assertThat(waiting.get("reason")).isNull();

            // The first node's VM leaves creation, and the third starts there.
            jdbcTemplate.update("update vms set status = 'RUNNING' where request_id = ? and node_id = ?",
                    requestId, nodes[0]);
            materializer.run();
            assertThat(vmNodes(requestId)).containsExactlyInAnyOrder(nodes[0], nodes[0], nodes[1]);
        } finally {
            retireTwoNodeImage(nodes);
        }
    }

    @Test
    void oneWaitingRecipientStopsItsRequestForTheRestOfTheRun() throws Exception {
        long[] nodes = twoNodeImage();
        try {
            UUID workspace = createWorkspace(ownerToken);
            List<User> recipients = List.of(addMember(workspace, "wait-1"), addMember(workspace, "wait-2"),
                    addMember(workspace, "wait-3"), addMember(workspace, "wait-4"));
            long requestId = approvedVmRequest(workspace, recipients, 1024);
            jdbcTemplate.update("""
                    insert into settings (key, value, description) values ('bulk_provision_concurrency', '1'::jsonb, 'test')
                    """);
            clearInvocations(cloneReservations);

            materializer.run();
            // One VM per node, then the third recipient waits and the fourth,
            // which would wait on the same nodes, is not placed at all.
            assertThat(vmCount(requestId)).isEqualTo(2);
            verify(cloneReservations, times(3)).reserve(eq(image.getPublicId()), any(), any(), any());
            assertThat(recipients.subList(2, 4).stream().map(user -> recipientStatus(requestId, user.getId())))
                    .containsExactly("QUEUED", "QUEUED");
        } finally {
            retireTwoNodeImage(nodes);
        }
    }

    @Test
    void aVmStillInCreationDoesNotHoldRecipientsWhenNoNodeIsActive() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User first = addMember(workspace, "inactive-1");
        User second = addMember(workspace, "inactive-2");
        long requestId = approvedVmRequest(workspace, List.of(first, second), 2048);
        jdbcTemplate.update("""
                insert into settings (key, value, description) values ('bulk_provision_concurrency', '1'::jsonb, 'test')
                """);
        materializer.run();
        assertThat(vmCount(requestId)).isEqualTo(1);

        List<Long> active = jdbcTemplate.queryForList("select id from nodes where status = 'ACTIVE'", Long.class);
        try {
            jdbcTemplate.update("update nodes set status = 'MAINTENANCE' where status = 'ACTIVE'");
            materializer.run();
            // The leftover CREATING VM's node is at the limit, but no node can
            // take anything, so the recipient fails as it would with none.
            assertThat(recipientStatus(requestId, second.getId())).isEqualTo("FAILED");
        } finally {
            for (Long nodeId : active) {
                jdbcTemplate.update("update nodes set status = 'ACTIVE' where id = ?", nodeId);
            }
        }
    }

    @Test
    void aPassedEndDateOrADepartedMemberCreatesNothing() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User expired = addMember(workspace, "expired");
        User departed = addMember(workspace, "departed");
        long expiredRequest = approvedKeyRequest(workspace, List.of(expired));
        jdbcTemplate.update("update request_reviews set granted_end_date = ? where request_id = ?",
                LocalDate.now(ClockConfig.KST).minusDays(1), expiredRequest);
        long departedRequest = approvedKeyRequest(workspace, List.of(departed));
        jdbcTemplate.update("delete from workspace_members where user_id = ?", departed.getId());

        materializer.run();
        assertThat(recipientStatus(expiredRequest, expired.getId())).isEqualTo("SKIPPED_EXPIRED");
        assertThat(recipientStatus(departedRequest, departed.getId())).isEqualTo("SKIPPED_INELIGIBLE");
        assertThat(keyCount(expiredRequest) + keyCount(departedRequest)).isZero();
    }

    @Test
    void keysAreCreatedForEachRecipientAndTheyAreTold() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        String workspaceName = jdbcTemplate.queryForObject("select name from workspaces where public_id = ?",
                String.class, workspace);
        User first = addMember(workspace, "key-1");
        User second = addMember(workspace, "key-2");
        long requestId = approvedKeyRequest(workspace, List.of(first, second, owner));
        String requestPath = "/console/requests/" + SeedFixtures.publicId(jdbcTemplate, "requests", requestId);

        // The requester hears how the recipients were settled, once.
        Map<String, Object> summary = jdbcTemplate.queryForMap("""
                select title, body from notifications
                 where user_id = ? and event = 'request.approved' and link_path = ?
                """, owner.getId(), requestPath);
        assertThat((String) summary.get("body"))
                .startsWith("리소스 '대상자 테스트' 신청이 승인되었습니다.\n")
                .contains("대상자 3명 중 3명의 리소스는 생성 대기열에 들어갔습니다.");

        materializer.run();
        assertThat(keyCount(requestId)).isEqualTo(3);
        for (User recipient : List.of(first, second, owner)) {
            Long keyId = jdbcTemplate.queryForObject(
                    "select resource_id from request_recipients where request_id = ? and user_id = ?",
                    Long.class, requestId, recipient.getId());
            assertThat(ownerOf("LLM_API_KEY", keyId)).isEqualTo(recipient.getId());
            assertThat(jdbcTemplate.queryForObject("select created_by from llm_api_keys where id = ?",
                    Long.class, keyId)).isEqualTo(recipient.getId());
        }
        // Recipients who never applied are told the key was granted to them,
        // not that a request of theirs was approved.
        for (User recipient : List.of(first, second)) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select n.event, n.body, n.link_path from notifications n
                      join llm_api_keys k on n.link_path = '/console/llm-keys/' || k.public_id
                     where n.user_id = ? and k.request_id = ?
                    """, recipient.getId(), requestId);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("event")).isEqualTo("resource.granted");
            assertThat((String) rows.get(0).get("body")).contains("'" + workspaceName + "' 워크스페이스에서");
            assertThat(jdbcTemplate.queryForObject("""
                    select count(*) from notifications where user_id = ? and event = 'request.approved'
                    """, Long.class, recipient.getId())).isZero();
        }
        // The requester named themselves too: their own key keeps the approval wording.
        assertThat(jdbcTemplate.queryForList("""
                select n.event from notifications n
                  join llm_api_keys k on n.link_path = '/console/llm-keys/' || k.public_id
                 where n.user_id = ? and k.request_id = ?
                """, String.class, owner.getId(), requestId)).containsExactly("request.approved");
    }

    @Test
    void anApproverWhoSubmitsAndApprovesGetsTheSummaryOnce() throws Exception {
        UUID workspace = linkedWorkspace();
        User member = addMember(workspace, "one-step-key");
        String invited = email("one-step-invitee");
        UUID invitation = invite(workspace, invited);
        Map<String, Object> body = common(workspace, "LLM_API_KEY");
        body.put("llmKey", Map.of());
        body.put("recipients", List.of(Map.of("userId", member.getPublicId()),
                Map.of("invitationId", invitation)));
        Map<String, Object> approval = new HashMap<>();
        approval.put("llmKey", Map.of());
        approval.put("grantedEndDate", LocalDate.now(ClockConfig.KST).plusMonths(1).toString());
        approval.put("comment", "한 번에 승인");
        body.put("approval", approval);

        JsonNode created = created(postJson("/api/v1/requests", orgAdminToken, body));
        List<String> bodies = jdbcTemplate.queryForList("""
                select body from notifications where user_id = ? and event = 'request.approved' and link_path = ?
                """, String.class, orgAdmin.getId(), "/console/requests/" + created.get("id").asString());
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("대상자 2명 중 1명의 리소스는 생성 대기열에 들어갔고 1명의 리소스는 가입하면 만들어집니다.")
                // The approver's own comment is not quoted back to them.
                .doesNotContain("검토 의견").doesNotContain("한 번에 승인");
    }

    @Test
    void noCapacityFailsTheRecipientAndRetryQueuesItAgain() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User member = addMember(workspace, "no-room");
        long requestId = approvedVmRequest(workspace, List.of(member), 50_000_000);

        materializer.run();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select public_id, status, reason, attempts from request_recipients where request_id = ?",
                requestId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("reason")).isNotBlank();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(vmCount(requestId)).isZero();

        String retry = "/api/v1/admin/requests/" + SeedFixtures.publicId(jdbcTemplate, "requests", requestId)
                + "/recipients/" + row.get("public_id") + "/retry";
        postJson(retry, ownerToken, Map.of()).andExpect(status().isForbidden());
        postJson(retry, orgAdminToken, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients[0].status").value("QUEUED"));
        postJson(retry, orgAdminToken, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_RECIPIENT_NOT_RETRYABLE"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'request.recipient_retry' and target_id = ?",
                Long.class, SeedFixtures.publicId(jdbcTemplate, "requests", requestId).toString())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- joining

    @Test
    void anInviteeWhoJoinsIsQueuedAndCreatedOnTheNextRun() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        String address = email("joiner");
        UUID invitation = invite(workspace, address);
        long requestId = approvedKeyRequestFor(workspace, List.of(Map.of("invitationId", invitation)));
        assertThat(recipientStatusByInvitation(requestId, invitation)).isEqualTo("PENDING_JOIN");

        User joiner = ensureUser(address, "나중가입");
        invitationClaimService.claimByEmail(joiner);
        assertThat(recipientStatusByInvitation(requestId, invitation)).isEqualTo("QUEUED");

        materializer.run();
        assertThat(recipientStatus(requestId, joiner.getId())).isEqualTo("CREATED");
        assertThat(keyCount(requestId)).isEqualTo(1);
    }

    @Test
    void anInviteeWhoJoinsAfterTheEndDateGetsNothing() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        String address = email("late-joiner");
        UUID invitation = invite(workspace, address);
        long requestId = approvedKeyRequestFor(workspace, List.of(Map.of("invitationId", invitation)));
        jdbcTemplate.update("update request_reviews set granted_end_date = ? where request_id = ?",
                LocalDate.now(ClockConfig.KST).minusDays(1), requestId);

        invitationClaimService.claimByEmail(ensureUser(address, "늦은가입"));
        assertThat(recipientStatusByInvitation(requestId, invitation)).isEqualTo("SKIPPED_EXPIRED");
        materializer.run();
        assertThat(keyCount(requestId)).isZero();
    }

    // ------------------------------------------------ closing what waits

    @Test
    void deletingAWorkspaceClosesTheRecipientsStillWaitingInIt() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User member = addMember(workspace, "deleted-ws");
        UUID invitation = invite(workspace, email("deleted-ws-invitee"));
        long requestId = approvedKeyRequestFor(workspace, List.of(
                Map.of("userId", member.getPublicId()), Map.of("invitationId", invitation)));

        mockMvc.perform(delete("/api/v1/workspaces/" + workspace).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbcTemplate.queryForList(
                "select status from request_recipients where request_id = ? order by id", String.class, requestId))
                .containsExactly("CANCELED", "CANCELED");
        assertThat(jdbcTemplate.queryForObject(
                "select reason from request_recipients where request_id = ? and user_id = ?",
                String.class, requestId, member.getId())).isEqualTo("워크스페이스가 삭제되었습니다.");
        materializer.run();
        assertThat(keyCount(requestId)).isZero();
    }

    /**
     * A deletion and a creation serialize on the workspace row. The deletion is
     * played here by a transaction that locks the row, stamps it deleted and
     * commits a moment later; a materializer run started meanwhile has to wait
     * for it and then find the workspace gone, rather than insert beside it.
     */
    @Test
    void theMaterializerWaitsForAWorkspaceDeletionHoldingTheRow() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User member = addMember(workspace, "held-ws");
        long requestId = approvedKeyRequest(workspace, List.of(member));
        long workspaceId = SeedFixtures.internalId(jdbcTemplate, "workspaces", workspace);

        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> deletion = pool.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                jdbcTemplate.queryForObject("select id from workspaces where id = ? for update", Long.class, workspaceId);
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbcTemplate.update("update workspaces set deleted_at = now(), deleted_by = ? where id = ?",
                        owner.getId(), workspaceId);
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<?> run = pool.submit(materializer::run);
            Thread.sleep(500);
            assertThat(run.isDone()).isFalse();
            assertThat(keyCount(requestId)).isZero();
            release.countDown();
            deletion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            run.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(keyCount(requestId)).isZero();
        assertThat(recipientStatus(requestId, member.getId())).isEqualTo("SKIPPED_INELIGIBLE");
    }

    @Test
    void cancelingAnInvitationClosesTheRecipientWaitingForIt() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        UUID invitation = invite(workspace, email("canceled-invitee"));
        long requestId = approvedKeyRequestFor(workspace, List.of(Map.of("invitationId", invitation)));

        mockMvc.perform(delete("/api/v1/workspaces/" + workspace + "/invitations/" + invitation)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().is2xxSuccessful());
        assertThat(recipientStatusByInvitation(requestId, invitation)).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject(
                "select reason from request_recipients where request_id = ?", String.class, requestId))
                .isEqualTo("초대가 취소되었습니다.");
    }

    // ------------------------------------------------------------ money

    @Test
    void aBulkKeyApprovalRecordsTheAllocationForEveryKeyItWillMake() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        User first = addMember(workspace, "credit-1");
        User second = addMember(workspace, "credit-2");
        UUID invitation = invite(workspace, email("credit-invitee"));
        long account = jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by) values (?, ?, ?) returning id
                """, Long.class, org.getId(), "대상자 금액 사업 " + UUID.randomUUID(), owner.getId());
        jdbcTemplate.update("""
                insert into openrouter_account_credentials (account_id, status, credential_enc,
                                                            created_by, activated_at, verified_at)
                values (?, 'ACTIVE'::openrouter_credential_status, ?, ?, now(), now())
                """, account, managementCipher.encrypt(SeedFixtures.publicId(jdbcTemplate, "openrouter_accounts", account),
                "management-credential-fixture"), owner.getId());

        Map<String, Object> body = common(workspace, "LLM_API_KEY");
        body.put("llmKey", Map.of("useCommercialModels", true));
        body.put("recipients", List.of(Map.of("userId", first.getPublicId()),
                Map.of("userId", second.getPublicId()), Map.of("invitationId", invitation)));
        JsonNode submitted = created(postJson("/api/v1/requests", ownerToken, body));
        Map<String, Object> approval = new HashMap<>();
        approval.put("llmKey", Map.of("grantedCreditLimit", "2.00",
                "openrouterAccountId", SeedFixtures.publicId(jdbcTemplate, "openrouter_accounts", account)));
        postJson("/api/v1/admin/requests/" + submitted.get("id").asString() + "/approve", orgAdminToken, approval)
                .andExpect(status().isOk());

        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                select detail ->> 'keysExpected' as keys,
                       detail ->> 'accountProjectedRemainingCommitment' as projected,
                       detail ->> 'accountRemainingCommitment' as remaining
                  from audit_logs where action = 'request.approve' and target_id = ?
                """, submitted.get("id").asString());
        assertThat(audit.get("keys")).isEqualTo("3");
        assertThat(new java.math.BigDecimal((String) audit.get("projected"))
                .subtract(new java.math.BigDecimal((String) audit.get("remaining"))))
                .isEqualByComparingTo("6.00");
    }

    // ------------------------------------------------ outside the workspace

    @Test
    void anApproverMayFileIntoAPersonalWorkspace() throws Exception {
        long personal = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('PERSONAL', ?) returning id", Long.class,
                "개인 " + UUID.randomUUID());
        jdbcTemplate.update("insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'OWNER')",
                personal, owner.getId());
        UUID workspace = SeedFixtures.publicId(jdbcTemplate, "workspaces", personal);
        Map<String, Object> body = vmBody(workspace, List.of(Map.of("userId", owner.getPublicId())));
        body.put("approval", vmApproval(2048));
        // PERSONAL included (operator decision, 2026-09-28).
        postJson("/api/v1/requests", orgAdminToken, body)
                .andExpect(status().isCreated());
    }

    @Test
    void anApproverOutsideTheWorkspaceFilesOnlyForOthers() throws Exception {
        UUID workspace = linkedWorkspace();
        Map<String, Object> body = vmBody(workspace, null);
        body.put("approval", vmApproval(2048));
        postJson("/api/v1/requests", orgAdminToken, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients')]").exists());
    }

    // ---------------------------------------------------------------- helpers

    private long approvedVmRequest(UUID workspace, List<User> recipients, int memoryMb) throws Exception {
        JsonNode submitted = created(postJson("/api/v1/requests", ownerToken, vmBody(workspace,
                recipients.stream().map(user -> Map.<String, Object>of("userId", user.getPublicId())).toList())));
        postJson("/api/v1/admin/requests/" + submitted.get("id").asString() + "/approve", orgAdminToken,
                vmApproval(memoryMb)).andExpect(status().isOk());
        return SeedFixtures.internalId(jdbcTemplate, "requests", UUID.fromString(submitted.get("id").asString()));
    }

    private long approvedKeyRequest(UUID workspace, List<User> recipients) throws Exception {
        return approvedKeyRequestFor(workspace,
                recipients.stream().map(user -> Map.<String, Object>of("userId", user.getPublicId())).toList());
    }

    private long approvedKeyRequestFor(UUID workspace, List<Map<String, Object>> recipients) throws Exception {
        Map<String, Object> body = common(workspace, "LLM_API_KEY");
        body.put("llmKey", Map.of());
        body.put("recipients", recipients);
        JsonNode submitted = created(postJson("/api/v1/requests", ownerToken, body));
        Map<String, Object> approval = new HashMap<>();
        approval.put("llmKey", Map.of());
        approval.put("grantedEndDate", LocalDate.now(ClockConfig.KST).plusMonths(1).toString());
        postJson("/api/v1/admin/requests/" + submitted.get("id").asString() + "/approve", orgAdminToken,
                approval).andExpect(status().isOk());
        return SeedFixtures.internalId(jdbcTemplate, "requests", UUID.fromString(submitted.get("id").asString()));
    }

    /** A workspace the seed organisation's approvers can reach: it holds one of its requests. */
    private UUID linkedWorkspace() throws Exception {
        UUID workspace = createWorkspace(ownerToken);
        postJson("/api/v1/requests", ownerToken, vmBody(workspace, null)).andExpect(status().isCreated());
        return workspace;
    }

    private String otherOrgAdminToken() {
        Org other = orgRepository.findFirstByNameOrderByIdAsc("대상자 타기관").orElseGet(() ->
                orgRepository.save(new Org("대상자 타기관", null)));
        User admin = userRepository.findByEmail("bulk.other.admin@pusan.ac.kr").orElseGet(() -> {
            User user = ensureUser("bulk.other.admin@pusan.ac.kr", "타기관관리자");
            user.setRole(UserRole.ORG_ADMIN);
            User saved = userRepository.save(user);
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), other.getId(), UserRole.ORG_ADMIN);
            return saved;
        });
        return jwtService.createAccessToken(admin);
    }

    private Map<String, Object> vmBody(UUID workspace, List<Map<String, Object>> recipients) {
        Map<String, Object> vm = new HashMap<>();
        vm.put("imageId", image.getPublicId());
        vm.put("reqVcpu", 1);
        vm.put("reqMemoryMb", 1024);
        vm.put("reqDiskGb", 32);
        Map<String, Object> body = common(workspace, "VM");
        body.put("vm", vm);
        if (recipients != null) {
            body.put("recipients", recipients);
        }
        return body;
    }

    private Map<String, Object> common(UUID workspace, String type) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("workspaceId", workspace);
        body.put("orgId", org.getPublicId());
        body.put("purpose", "대상자 신청 테스트");
        body.put("reqEndDate", LocalDate.now(ClockConfig.KST).plusMonths(4).toString());
        body.put("displayName", "대상자 테스트");
        return body;
    }

    private Map<String, Object> vmApproval(int memoryMb) {
        Map<String, Object> vm = new HashMap<>();
        vm.put("grantedVcpu", 1);
        vm.put("grantedMemoryMb", memoryMb);
        vm.put("grantedDiskGb", 32);
        vm.put("grantedImageId", image.getPublicId());
        Map<String, Object> body = new HashMap<>();
        body.put("vm", vm);
        body.put("grantedEndDate", LocalDate.now(ClockConfig.KST).plusMonths(4).toString());
        return body;
    }

    private static List<String> statuses(JsonNode detail) {
        List<String> out = new ArrayList<>();
        detail.get("recipients").forEach(node -> out.add(node.get("status").asString()));
        return out;
    }

    /**
     * Two fresh nodes, each holding a copy of a fresh image that becomes this
     * test's {@link #image}. The second is labelled a GPU node, which
     * placement ranks last, so a VM goes there only when the first is ruled out.
     */
    private long[] twoNodeImage() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long poolId = jdbcTemplate.queryForObject("select min(id) from ip_pools", Long.class);
        long first = insertNode("bulk-a-" + suffix, "{}", poolId);
        long second = insertNode("bulk-b-" + suffix, "{\"gpu\":true}", poolId);
        String name = "bulk-os-" + suffix;
        int vmid = 991000 + (int) Math.floorMod(suffix.hashCode(), 8000);
        image = imageRepository.saveAndFlush(new OsImage(name, "Bulk OS", "ubuntu", "24.04", "ubuntu",
                vmid, first, 1, 10, CatalogStatus.ACTIVE, null));
        imageRepository.saveAndFlush(new OsImage(name, "Bulk OS", "ubuntu", "24.04", "ubuntu",
                vmid + 1, second, 1, 10, CatalogStatus.ACTIVE, null));
        return new long[] {first, second};
    }

    /**
     * Takes what {@link #twoNodeImage} added out of every listing another test
     * could read: the nodes leave ACTIVE, their image copies are disabled, and
     * any VM still CREATING on them stops holding a slot of the limit.
     */
    private void retireTwoNodeImage(long[] nodes) {
        jdbcTemplate.update("update os_images set status = 'DISABLED' where node_id in (?, ?)", nodes[0], nodes[1]);
        jdbcTemplate.update("update vms set status = 'RUNNING' where status = 'CREATING' and node_id in (?, ?)",
                nodes[0], nodes[1]);
        jdbcTemplate.update("update nodes set status = 'MAINTENANCE' where id in (?, ?)", nodes[0], nodes[1]);
    }

    private long insertNode(String name, String extraLabels, long poolId) {
        return jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, status, cpu_threads, memory_mb, labels,
                                   vm_bridge, storage, ip_pool_id, disk_capacity_gb)
                values (?, 'https://127.0.0.1:8006', 'ACTIVE', 32, 57344,
                        cast(? as jsonb) || cast(? as jsonb), 'vmbr2', 'local-lvm', ?, 1000)
                returning id
                """, Long.class, name, """
                {"placement_capacity":{"schema_version":1,"measured_at":"2026-09-18T00:00:00Z",
                "physical":{"cpu_threads":32,"memory_mb":65536,"disk_gb":1000},
                "reserved":{"cpu_threads":4,"memory_mb":8192,"disk_gb":200},
                "allocatable":{"cpu_threads":28,"memory_mb":57344,"disk_gb":800}},
                "vm_nic_requirements":{"schema_version":1,"mtu":1370,"firewall":true}}
                """, extraLabels, poolId);
    }

    private List<Long> vmNodes(long requestId) {
        return jdbcTemplate.queryForList("select node_id from vms where request_id = ?", Long.class, requestId);
    }

    private long vmCount(long requestId) {
        return jdbcTemplate.queryForObject("select count(*) from vms where request_id = ?", Long.class, requestId);
    }

    private long keyCount(long requestId) {
        return jdbcTemplate.queryForObject("select count(*) from llm_api_keys where request_id = ?",
                Long.class, requestId);
    }

    private Long ownerOf(String type, long resourceId) {
        return jdbcTemplate.queryForObject("""
                select user_id from resource_access_grants
                 where resource_type = ?::resource_type and resource_id = ? and role = 'OWNER'
                """, Long.class, type, resourceId);
    }

    private String recipientStatus(long requestId, long userId) {
        return jdbcTemplate.queryForObject(
                "select status from request_recipients where request_id = ? and user_id = ?",
                String.class, requestId, userId);
    }

    private String recipientStatusByInvitation(long requestId, UUID invitation) {
        return jdbcTemplate.queryForObject("""
                select rr.status from request_recipients rr
                  join workspace_invitations i on i.id = rr.invitation_id
                 where rr.request_id = ? and i.public_id = ?
                """, String.class, requestId, invitation);
    }

    private JsonNode created(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private UUID createWorkspace(String token) throws Exception {
        String body = postJson("/api/v1/workspaces", token,
                Map.of("kind", "PROJECT", "name", "대상자 테스트 " + (++sequence) + "-" + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private User addMember(UUID workspace, String label) throws Exception {
        User user = ensureUser(email(label), "구성원 " + label);
        invitations(workspace, user.getEmail())
                .andExpect(jsonPath("$.results[0].outcome").value("ADDED"));
        return user;
    }

    private UUID invite(UUID workspace, String address) throws Exception {
        invitations(workspace, address).andExpect(jsonPath("$.results[0].outcome").value("INVITED"));
        return jdbcTemplate.queryForObject("""
                select i.public_id from workspace_invitations i join workspaces w on w.id = i.workspace_id
                 where w.public_id = ? and i.invitee_email = ?
                """, UUID.class, workspace, address);
    }

    private ResultActions invitations(UUID workspace, String address) throws Exception {
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'workspace_invite%'");
        return mockMvc.perform(post("/api/v1/workspaces/" + workspace + "/invitations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("entries", List.of(Map.of("email", address))))))
                .andExpect(status().isOk());
    }

    private String email(String label) {
        return "bulk." + label + "@pusan.ac.kr";
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private User ensureUser(String address, String name) {
        return userRepository.findByEmail(address).orElseGet(() -> {
            User user = new User(address, "{test-no-login}", name);
            user.setRole(UserRole.USER);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
