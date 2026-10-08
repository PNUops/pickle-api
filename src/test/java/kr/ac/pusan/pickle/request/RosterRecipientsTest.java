package kr.ac.pusan.pickle.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
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
 * Recipients named by 학번: the read-only roster lookup that tells the caller
 * what submitting will do, who may make it, and the submission that turns
 * each 학번 into a member or an invitation and carries it through approval
 * and a later sign-up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class RosterRecipientsTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RequestRecipientMaterializer materializer;

    @Autowired
    private InvitationClaimService invitationClaimService;

    private final String run = UUID.randomUUID().toString().replace("-", "");
    private Org org;
    private User owner;
    private String ownerToken;
    private String orgAdminToken;

    @BeforeEach
    void setUp() {
        org = orgRepository.findFirstByNameOrderByIdAsc(SeedFixtures.ORG_NAME).orElseThrow();
        owner = user("owner", null);
        ownerToken = jwtService.createAccessToken(owner);
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        jdbcTemplate.update("update request_recipients set status = 'CANCELED' where status = 'QUEUED'");
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'workspace_invite%'");
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'workspace_roster_resolve%'");
    }

    // ---------------------------------------------------------------- resolve

    @Test
    void resolveReportsWhereEachStudentNumberStandsAndWritesNothing() throws Exception {
        UUID workspace = createWorkspace();
        User member = addMember(workspace, user("member", studentNo()));
        User registered = user("registered", studentNo());
        String invited = studentNo();
        UUID invitation = inviteStudentNo(workspace, invited);
        String fresh = studentNo();
        long members = count("workspace_members");
        long invitations = count("workspace_invitations");

        JsonNode results = resolve(ownerToken, workspace, null, List.of(
                " " + member.getStudentNo() + " ", registered.getStudentNo(), invited, fresh, "12",
                member.getStudentNo().toLowerCase()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().transform(this::results);

        assertThat(statuses(results)).containsExactly("MEMBER", "REGISTERED", "INVITED", "NEW", "INVALID",
                "DUPLICATE");
        assertThat(results.get(0).get("studentNo").asString()).isEqualTo(member.getStudentNo());
        assertThat(results.get(0).get("userId").asString()).isEqualTo(member.getPublicId().toString());
        assertThat(results.get(0).get("name").asString()).isEqualTo(member.getName());
        // A registered non-member is a status only: no account id, no name.
        assertThat(results.get(1).get("userId").isNull()).isTrue();
        assertThat(results.get(1).get("name").isNull()).isTrue();
        assertThat(results.get(2).get("invitationId").asString()).isEqualTo(invitation.toString());
        assertThat(results.get(3).get("userId").isNull()).isTrue();
        assertThat(results.get(3).get("invitationId").isNull()).isTrue();
        assertThat(count("workspace_members")).isEqualTo(members);
        assertThat(count("workspace_invitations")).isEqualTo(invitations);
    }

    /** An account that is not ACTIVE answers as a missing one does, as an invitation does. */
    @Test
    void anInactiveAccountReadsAsNew() throws Exception {
        UUID workspace = createWorkspace();
        User pending = user("pending", studentNo());
        pending.setStatus(UserStatus.PENDING_VERIFICATION);
        userRepository.saveAndFlush(pending);

        JsonNode results = resolve(ownerToken, workspace, null, List.of(pending.getStudentNo()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().transform(this::results);
        assertThat(statuses(results)).containsExactly("NEW");
        assertThat(results.get(0).get("name").isNull()).isTrue();
    }

    @Test
    void resolveIsForOwnersAndTheOrganisationsApprovers() throws Exception {
        UUID workspace = createWorkspace();
        User plain = addMember(workspace, user("plain", studentNo()));
        List<String> roster = List.of(studentNo());

        resolve(ownerToken, workspace, null, roster).andExpect(status().isOk());
        resolve(orgAdminToken, workspace, org.getPublicId(), roster).andExpect(status().isOk());
        // An approver acts for an organisation, so it has to say which.
        resolve(orgAdminToken, workspace, null, roster)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REQUEST_RECIPIENTS_FORBIDDEN"));
        resolve(jwtService.createAccessToken(plain), workspace, org.getPublicId(), roster)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REQUEST_RECIPIENTS_FORBIDDEN"));
        resolve(otherOrgAdminToken(), workspace, org.getPublicId(), roster)
                .andExpect(status().isForbidden());
        // A workspace is not narrowed to one organisation (operator decision,
        // 2026-09-28): another organisation's approver acting for their own
        // organisation reaches it as a submission would.
        resolve(otherOrgAdminToken(), workspace, otherOrg().getPublicId(), roster)
                .andExpect(status().isOk());

        long personal = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('PERSONAL', ?) returning id", Long.class,
                "개인 " + UUID.randomUUID());
        jdbcTemplate.update("insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'OWNER')",
                personal, owner.getId());
        UUID personalId = SeedFixtures.publicId(jdbcTemplate, "workspaces", personal);
        resolve(ownerToken, personalId, null, roster).andExpect(status().isForbidden());
        resolve(orgAdminToken, personalId, org.getPublicId(), roster).andExpect(status().isForbidden());
    }

    @Test
    void resolveIsCappedPerMinute() throws Exception {
        UUID workspace = createWorkspace();
        for (int i = 0; i < RosterService.RESOLVE_CALLS_PER_MINUTE; i++) {
            resolve(ownerToken, workspace, null, List.of(studentNo())).andExpect(status().isOk());
        }
        resolve(ownerToken, workspace, null, List.of(studentNo())).andExpect(status().isTooManyRequests());
    }

    @Test
    void resolveChargesAnHourlyBudgetOfStudentNumbers() throws Exception {
        UUID workspace = createWorkspace();
        jdbcTemplate.update("""
                insert into auth_rate_limits (scope, subject, window_start, request_count)
                values ('workspace_roster_resolve_entries', ?,
                        date_bin(interval '15 minutes', now(), timestamptz 'epoch'), 1999)
                """, "user:" + owner.getId());

        resolve(ownerToken, workspace, null, List.of(studentNo(), studentNo()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // A refused request spends nothing: the one entry left still fits.
        resolve(ownerToken, workspace, null, List.of(studentNo())).andExpect(status().isOk());
        jdbcTemplate.update("delete from auth_rate_limits where scope = 'workspace_roster_resolve_entries'");
    }

    // ------------------------------------------------------------- submission

    @Test
    void studentNumberRecipientsBecomeMembersOrInviteesAndGetTheirKeys() throws Exception {
        UUID workspace = createWorkspace();
        User member = addMember(workspace, user("sn-member", studentNo()));
        User registered = user("sn-registered", studentNo());
        String invited = studentNo();
        UUID existingInvitation = inviteStudentNo(workspace, invited);
        String fresh = studentNo();
        long workspaceId = SeedFixtures.internalId(jdbcTemplate, "workspaces", workspace);

        JsonNode submitted = created(postJson("/api/v1/requests", ownerToken, keyBody(workspace, List.of(
                Map.of("studentNo", member.getStudentNo()),
                Map.of("studentNo", registered.getStudentNo().toLowerCase()),
                Map.of("studentNo", invited),
                Map.of("studentNo", " " + fresh)))));
        assertThat(statuses(submitted.get("recipients")))
                .containsExactly("QUEUED", "QUEUED", "PENDING_JOIN", "PENDING_JOIN");
        long requestId = SeedFixtures.internalId(jdbcTemplate, "requests",
                UUID.fromString(submitted.get("id").asString()));

        // The registered account is a member now, added as an invitation adds one.
        assertThat(jdbcTemplate.queryForObject(
                "select role::text from workspace_members where workspace_id = ? and user_id = ?",
                String.class, workspaceId, registered.getId())).isEqualTo("MEMBER");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_logs where action = 'workspace.member_add' and target_id = ?
                   and detail ->> 'userId' = ? and detail ->> 'viaRequest' = 'true' and detail ->> 'requestId' = ?
                """, Long.class, workspace.toString(), registered.getPublicId().toString(),
                submitted.get("id").asString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_logs where action = 'workspace.invitation_create' and target_id = ?
                   and detail ->> 'viaRequest' = 'true' and detail ->> 'requestId' = ?
                """, Long.class, workspace.toString(), submitted.get("id").asString())).isEqualTo(1);
        // The existing invitation is reused, and one is opened for the 학번 with nothing.
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from request_recipients rr join workspace_invitations i on i.id = rr.invitation_id
                 where rr.request_id = ? and i.public_id = ?
                """, Long.class, requestId, existingInvitation)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from workspace_invitations
                 where workspace_id = ? and invitee_student_no = ? and status = 'PENDING'
                """, Long.class, workspaceId, fresh)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_logs where action = 'workspace.invitation_create' and target_id = ?
                """, Long.class, workspace.toString())).isEqualTo(2);

        approveKey(submitted.get("id").asString());
        materializer.run();
        assertThat(recipientStatus(requestId, member.getId())).isEqualTo("CREATED");
        assertThat(recipientStatus(requestId, registered.getId())).isEqualTo("CREATED");

        // The newcomer signs up with that 학번 and gets theirs on the next run.
        User newcomer = user("sn-newcomer", fresh);
        invitationClaimService.claimByStudentNo(newcomer);
        materializer.run();
        assertThat(recipientStatus(requestId, newcomer.getId())).isEqualTo("CREATED");
    }

    @Test
    void anApproverOutsideTheWorkspaceAddsStudentNumbersWhenDeciding() throws Exception {
        UUID workspace = createWorkspace();
        User registered = user("approver-added", studentNo());
        Map<String, Object> body = keyBody(workspace, List.of(Map.of("studentNo", registered.getStudentNo())));
        Map<String, Object> approval = new HashMap<>();
        approval.put("llmKey", Map.of());
        approval.put("grantedEndDate", LocalDate.now(ClockConfig.KST).plusMonths(1).toString());
        body.put("approval", approval);

        JsonNode created = created(postJson("/api/v1/requests", orgAdminToken, body));
        assertThat(created.get("status").asString()).isEqualTo("APPROVED");
        assertThat(statuses(created.get("recipients"))).containsExactly("QUEUED");
        long workspaceId = SeedFixtures.internalId(jdbcTemplate, "workspaces", workspace);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from workspace_members where workspace_id = ? and user_id = ?",
                Long.class, workspaceId, registered.getId())).isEqualTo(1);
        // The approver is told how the recipients were settled.
        assertThat(jdbcTemplate.queryForObject("""
                select body from notifications where user_id = ? and event = 'request.approved' and link_path = ?
                """, String.class, orgAdmin().getId(), "/console/requests/" + created.get("id").asString()))
                .contains("대상자 1명 중 1명의 리소스는 생성 대기열에 들어갔습니다.");
    }

    /** Only people the submission really added or invited are charged, not 학번 already settled. */
    @Test
    void theBudgetIsChargedForAddedAndInvitedPeopleOnly() throws Exception {
        UUID workspace = createWorkspace();
        User member = addMember(workspace, user("charge-member", studentNo()));
        String invited = studentNo();
        inviteStudentNo(workspace, invited);
        User registered = user("charge-registered", studentNo());
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'workspace_invite%'");

        created(postJson("/api/v1/requests", ownerToken, keyBody(workspace, List.of(
                Map.of("studentNo", member.getStudentNo()),
                Map.of("studentNo", invited),
                Map.of("studentNo", registered.getStudentNo()),
                Map.of("studentNo", studentNo())))));
        assertThat(jdbcTemplate.queryForObject("""
                select coalesce(sum(request_count), 0) from auth_rate_limits
                 where scope = 'workspace_invite_entries' and subject = ?
                """, Long.class, "user:" + owner.getId())).isEqualTo(2);
    }

    /** A refused approval rolls the placements back, and the budget is not spent on them. */
    @Test
    void aRefusedApprovalChargesNoInvitationBudget() throws Exception {
        UUID workspace = createWorkspace();
        String fresh = studentNo();
        Map<String, Object> body = keyBody(workspace, List.of(Map.of("studentNo", fresh)));
        Map<String, Object> approval = new HashMap<>();
        approval.put("llmKey", Map.of());
        approval.put("grantedStartDate", LocalDate.now(ClockConfig.KST).plusMonths(2).toString());
        approval.put("grantedEndDate", LocalDate.now(ClockConfig.KST).plusMonths(1).toString());
        body.put("approval", approval);

        postJson("/api/v1/requests", orgAdminToken, body).andExpect(status().isUnprocessableContent());
        assertThat(jdbcTemplate.queryForObject("""
                select coalesce(sum(request_count), 0) from auth_rate_limits
                 where scope = 'workspace_invite_entries' and subject = ?
                """, Long.class, "user:" + orgAdmin().getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from workspace_invitations where invitee_student_no = ?", Long.class, fresh))
                .isZero();
    }

    /**
     * What a submission placed outlives the request: a rejected or canceled
     * one leaves its members and invitations, and its recipient rows stay
     * closed whatever later happens to those invitations.
     */
    @Test
    void membersAndInvitationsOutliveARejectedOrCanceledRequest() throws Exception {
        UUID workspace = createWorkspace();
        long workspaceId = SeedFixtures.internalId(jdbcTemplate, "workspaces", workspace);
        User registered = user("outlive-registered", studentNo());
        String joiner = studentNo();
        String dropped = studentNo();

        JsonNode rejected = created(postJson("/api/v1/requests", ownerToken, keyBody(workspace, List.of(
                Map.of("studentNo", registered.getStudentNo()), Map.of("studentNo", joiner)))));
        postJson("/api/v1/admin/requests/" + rejected.get("id").asString() + "/reject", orgAdminToken,
                Map.of("comment", "이번 학기에는 지원하지 않습니다.")).andExpect(status().isOk());
        JsonNode canceled = created(postJson("/api/v1/requests", ownerToken, keyBody(workspace, List.of(
                Map.of("studentNo", dropped)))));
        postJson("/api/v1/requests/" + canceled.get("id").asString() + "/cancel", ownerToken, Map.of())
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from workspace_members where workspace_id = ? and user_id = ?",
                Long.class, workspaceId, registered.getId())).isEqualTo(1);
        for (String studentNo : List.of(joiner, dropped)) {
            assertThat(jdbcTemplate.queryForObject("""
                    select status::text from workspace_invitations where workspace_id = ? and invitee_student_no = ?
                    """, String.class, workspaceId, studentNo)).isEqualTo("PENDING");
        }
        for (JsonNode request : List.of(rejected, canceled)) {
            assertThat(recipientStatuses(request)).containsOnly("CANCELED");
        }

        // The invitee joins; the closed row is not reopened for them.
        invitationClaimService.claimByStudentNo(user("outlive-joiner", joiner));
        // The other invitation is canceled; the closed row keeps its own reason.
        UUID droppedInvitation = jdbcTemplate.queryForObject("""
                select public_id from workspace_invitations where workspace_id = ? and invitee_student_no = ?
                """, UUID.class, workspaceId, dropped);
        mockMvc.perform(delete("/api/v1/workspaces/" + workspace + "/invitations/" + droppedInvitation)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        for (JsonNode request : List.of(rejected, canceled)) {
            assertThat(recipientStatuses(request)).containsOnly("CANCELED");
            assertThat(jdbcTemplate.queryForList("""
                    select rr.reason from request_recipients rr join requests r on r.id = rr.request_id
                     where r.public_id = ?
                    """, String.class, UUID.fromString(request.get("id").asString()))).containsOnlyNulls();
        }
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from request_recipients rr join requests r on r.id = rr.request_id
                 where r.public_id = ? and rr.user_id is not null and rr.invitation_id is not null
                """, Long.class, UUID.fromString(rejected.get("id").asString()))).isZero();
    }

    @Test
    void aPlainMemberCannotNameStudentNumbers() throws Exception {
        UUID workspace = createWorkspace();
        User plain = addMember(workspace, user("sn-plain", studentNo()));
        User registered = user("sn-plain-target", studentNo());
        long invitations = count("workspace_invitations");
        long members = count("workspace_members");

        postJson("/api/v1/requests", jwtService.createAccessToken(plain),
                keyBody(workspace, List.of(Map.of("studentNo", studentNo()),
                        Map.of("studentNo", registered.getStudentNo()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REQUEST_RECIPIENTS_FORBIDDEN"));
        assertThat(count("workspace_invitations")).isEqualTo(invitations);
        assertThat(count("workspace_members")).isEqualTo(members);
    }

    @Test
    void badStudentNumberEntriesAreFieldErrors() throws Exception {
        UUID workspace = createWorkspace();
        User member = addMember(workspace, user("sn-dup", studentNo()));

        postJson("/api/v1/requests", ownerToken, keyBody(workspace, List.of(
                Map.of("studentNo", "12"),
                Map.of("userId", member.getPublicId()),
                Map.of("studentNo", member.getStudentNo()),
                Map.of("studentNo", studentNo(), "userId", member.getPublicId()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients[0].studentNo')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients[2]')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients[3]')]").exists());
    }

    @Test
    void aRosterOverTheHourlyInvitationBudgetIsRefusedWritingNothing() throws Exception {
        UUID workspace = createWorkspace();
        jdbcTemplate.update("""
                insert into auth_rate_limits (scope, subject, window_start, request_count)
                values ('workspace_invite_entries', ?, date_bin(interval '15 minutes', now(), timestamptz 'epoch'), 499)
                """, "user:" + owner.getId());
        User registered = user("over-budget", studentNo());
        long requests = count("requests");
        long invitations = count("workspace_invitations");
        long members = count("workspace_members");

        postJson("/api/v1/requests", ownerToken, keyBody(workspace, List.of(
                Map.of("studentNo", registered.getStudentNo()),
                Map.of("studentNo", studentNo()))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(count("requests")).isEqualTo(requests);
        assertThat(count("workspace_invitations")).isEqualTo(invitations);
        assertThat(count("workspace_members")).isEqualTo(members);
    }

    @Test
    void aRequestNamesAtMostTwoHundredRecipients() throws Exception {
        UUID workspace = createWorkspace();
        List<Map<String, Object>> recipients = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            recipients.add(Map.of("studentNo", studentNo()));
        }
        long invitations = count("workspace_invitations");

        postJson("/api/v1/requests", ownerToken, keyBody(workspace, recipients))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipients')]").exists());
        assertThat(count("workspace_invitations")).isEqualTo(invitations);
    }

    /** Inviting someone who is already a member names them, as ADDED does. */
    @Test
    void anAlreadyMemberInvitationResultCarriesTheAccount() throws Exception {
        UUID workspace = createWorkspace();
        User member = addMember(workspace, user("again", studentNo()));

        invitations(workspace, Map.of("studentNo", member.getStudentNo()))
                .andExpect(jsonPath("$.results[0].outcome").value("ALREADY_MEMBER"))
                .andExpect(jsonPath("$.results[0].userId").value(member.getPublicId().toString()));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions resolve(String token, UUID workspace, UUID orgId, List<String> studentNos)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("studentNos", studentNos);
        if (orgId != null) {
            body.put("orgId", orgId);
        }
        return postJson("/api/v1/workspaces/" + workspace + "/roster/resolve", token, body);
    }

    private JsonNode results(String body) {
        return objectMapper.readTree(body).get("results");
    }

    private static List<String> statuses(JsonNode nodes) {
        List<String> out = new ArrayList<>();
        nodes.forEach(node -> out.add(node.get("status").asString()));
        return out;
    }

    private Map<String, Object> keyBody(UUID workspace, List<Map<String, Object>> recipients) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "LLM_API_KEY");
        body.put("workspaceId", workspace);
        body.put("orgId", org.getPublicId());
        body.put("purpose", "학번 대상자 테스트");
        body.put("reqEndDate", LocalDate.now(ClockConfig.KST).plusMonths(4).toString());
        body.put("displayName", "학번 대상자");
        body.put("llmKey", Map.of());
        body.put("recipients", recipients);
        return body;
    }

    private void approveKey(String requestId) throws Exception {
        Map<String, Object> approval = new HashMap<>();
        approval.put("llmKey", Map.of());
        approval.put("grantedEndDate", LocalDate.now(ClockConfig.KST).plusMonths(1).toString());
        postJson("/api/v1/admin/requests/" + requestId + "/approve", orgAdminToken, approval)
                .andExpect(status().isOk());
    }

    private String recipientStatus(long requestId, long userId) {
        return jdbcTemplate.queryForObject(
                "select status from request_recipients where request_id = ? and user_id = ?",
                String.class, requestId, userId);
    }

    private long count(String table) {
        // The table name is one of this class's literals, never input.
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private UUID createWorkspace() throws Exception {
        String body = postJson("/api/v1/workspaces", ownerToken,
                Map.of("kind", "PROJECT", "name", "학번 대상자 " + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private User addMember(UUID workspace, User user) throws Exception {
        invitations(workspace, Map.of("email", user.getEmail()))
                .andExpect(jsonPath("$.results[0].outcome").value("ADDED"));
        return user;
    }

    private UUID inviteStudentNo(UUID workspace, String studentNo) throws Exception {
        String body = invitations(workspace, Map.of("studentNo", studentNo))
                .andExpect(jsonPath("$.results[0].outcome").value("INVITED"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("results").get(0).get("invitationId").asString());
    }

    private ResultActions invitations(UUID workspace, Map<String, Object> entry) throws Exception {
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'workspace_invite%'");
        return postJson("/api/v1/workspaces/" + workspace + "/invitations", ownerToken,
                Map.of("entries", List.of(entry)))
                .andExpect(status().isOk());
    }

    private Org otherOrg() {
        return orgRepository.findFirstByNameOrderByIdAsc("학번 타기관").orElseGet(() ->
                orgRepository.save(new Org("학번 타기관", null)));
    }

    private User orgAdmin() {
        return userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow();
    }

    private List<String> recipientStatuses(JsonNode request) {
        return jdbcTemplate.queryForList("""
                select rr.status from request_recipients rr join requests r on r.id = rr.request_id
                 where r.public_id = ?
                """, String.class, UUID.fromString(request.get("id").asString()));
    }

    private String otherOrgAdminToken() {
        Org other = otherOrg();
        User admin = userRepository.findByEmail("roster.other-admin@pusan.ac.kr").orElseGet(() -> {
            User user = user("other-admin", null);
            user.setRole(UserRole.ORG_ADMIN);
            User saved = userRepository.save(user);
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), other.getId(), UserRole.ORG_ADMIN);
            return saved;
        });
        return jwtService.createAccessToken(admin);
    }

    private String studentNo() {
        return "rs" + run.substring(0, 6) + SEQ.incrementAndGet();
    }

    private JsonNode created(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** An ACTIVE account, a student holding {@code studentNo} when one is given. */
    private User user(String label, String studentNo) {
        String address = "roster." + label + ("owner".equals(label) || "other-admin".equals(label) ? "" : "." + run)
                + "@pusan.ac.kr";
        return userRepository.findByEmail(address).orElseGet(() -> {
            User user = new User(address, "{test-no-login}", "명단 " + label);
            user.setRole(UserRole.USER);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            if (studentNo != null) {
                user.setProfile(UserPosition.STUDENT_UNDERGRAD, studentNo, null, null);
            }
            return userRepository.saveAndFlush(user);
        });
    }
}
