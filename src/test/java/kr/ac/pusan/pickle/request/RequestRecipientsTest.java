package kr.ac.pusan.pickle.request;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired
    private InvitationClaimService invitationClaimService;

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
    void anApproverFilesOnlyWhereTheAdminScreensReach() throws Exception {
        // A workspace with nothing of this organisation in it is outside the
        // approver's scope, the same 404 the admin workspace reads give.
        UUID unlinked = createWorkspace(ownerToken);
        Map<String, Object> body = vmBody(unlinked, null);
        body.put("approval", vmApproval(2048));
        postJson("/api/v1/requests", orgAdminToken, body)
                .andExpect(status().isNotFound());
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
        mockMvc.perform(get(uri).header("Authorization", "Bearer " + otherOrgAdminToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/workspaces/" + createWorkspace(ownerToken) + "/invitations")
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

        // Once the first leaves creation, the next one starts.
        jdbcTemplate.update("update vms set status = 'RUNNING' where request_id = ?", requestId);
        materializer.run();
        assertThat(vmCount(requestId)).isEqualTo(2);
        assertThat(ownerOf("VM", jdbcTemplate.queryForObject(
                "select resource_id from request_recipients where request_id = ? and user_id = ?",
                Long.class, requestId, second.getId()))).isEqualTo(second.getId());
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
        User first = addMember(workspace, "key-1");
        User second = addMember(workspace, "key-2");
        long requestId = approvedKeyRequest(workspace, List.of(first, second));

        materializer.run();
        assertThat(keyCount(requestId)).isEqualTo(2);
        for (User recipient : List.of(first, second)) {
            Long keyId = jdbcTemplate.queryForObject(
                    "select resource_id from request_recipients where request_id = ? and user_id = ?",
                    Long.class, requestId, recipient.getId());
            assertThat(ownerOf("LLM_API_KEY", keyId)).isEqualTo(recipient.getId());
            assertThat(jdbcTemplate.queryForObject("select created_by from llm_api_keys where id = ?",
                    Long.class, keyId)).isEqualTo(recipient.getId());
            assertThat(jdbcTemplate.queryForObject("""
                    select count(*) from notifications where user_id = ? and event = 'request.approved'
                    """, Long.class, recipient.getId())).isEqualTo(1);
        }
        // The requester owns none of them.
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from resource_access_grants g join llm_api_keys k on k.id = g.resource_id
                 where g.resource_type = 'LLM_API_KEY'::resource_type and k.request_id = ? and g.user_id = ?
                """, Long.class, requestId, owner.getId())).isZero();
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
