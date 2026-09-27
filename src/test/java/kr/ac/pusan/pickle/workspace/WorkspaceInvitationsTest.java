package kr.ac.pusan.pickle.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.auth.TokenHasher;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.UserRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bulk invitation by email or 학번, the pending-invitation list and cancel,
 * and the claim that turns an open invitation into a membership when the
 * matching account becomes usable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class WorkspaceInvitationsTest {

    /** Unique per case: users.email and users.student_no are both unique across the shared database. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PersonalWorkspaceService personalWorkspaceService;
    @Autowired
    private InvitationClaimService invitationClaimService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private String ownerToken;
    private String run;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from auth_rate_limits where scope like 'workspace_invite%'");
        run = UUID.randomUUID().toString().substring(0, 8);
        owner = activeUser("owner");
        ownerToken = jwtService.createAccessToken(owner);
    }

    // ------------------------------------------------------------ outcomes

    @Test
    void eachEntryAnswersWithItsOwnOutcomeInRequestOrder() throws Exception {
        UUID workspace = createWorkspace();
        User byEmail = activeUser("byemail");
        User byStudentNo = activeStudent("bystudent");
        User already = activeUser("already");
        invite(workspace, List.of(Map.of("email", already.getEmail()))).andExpect(status().isOk());
        String unknownEmail = email("nobody");
        String unknownStudentNo = studentNo();
        invite(workspace, List.of(Map.of("studentNo", "  " + unknownStudentNo + " "))).andExpect(status().isOk());

        invite(workspace, List.of(
                Map.of("email", "  " + byEmail.getEmail().toUpperCase() + " "),
                Map.of("studentNo", byStudentNo.getStudentNo().toLowerCase()),
                Map.of("email", already.getEmail()),
                Map.of("email", unknownEmail),
                Map.of("studentNo", unknownStudentNo.toUpperCase()),
                Map.of("email", byEmail.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(6))
                .andExpect(jsonPath("$.results[0].outcome").value("ADDED"))
                .andExpect(jsonPath("$.results[0].email").value(byEmail.getEmail()))
                .andExpect(jsonPath("$.results[0].userId").value(byEmail.getPublicId().toString()))
                .andExpect(jsonPath("$.results[0].invitationId").doesNotExist())
                // the added account is identified by id only: no name or profile field
                .andExpect(jsonPath("$.results[0].name").doesNotExist())
                .andExpect(jsonPath("$.results[1].outcome").value("ADDED"))
                .andExpect(jsonPath("$.results[1].userId").value(byStudentNo.getPublicId().toString()))
                .andExpect(jsonPath("$.results[2].outcome").value("ALREADY_MEMBER"))
                .andExpect(jsonPath("$.results[3].outcome").value("INVITED"))
                .andExpect(jsonPath("$.results[3].invitationId").isNotEmpty())
                .andExpect(jsonPath("$.results[3].userId").doesNotExist())
                .andExpect(jsonPath("$.results[4].outcome").value("ALREADY_INVITED"))
                .andExpect(jsonPath("$.results[4].invitationId").isNotEmpty())
                .andExpect(jsonPath("$.results[5].outcome").value("DUPLICATE_IN_REQUEST"));

        assertThat(isMember(workspace, byEmail)).isTrue();
        assertThat(isMember(workspace, byStudentNo)).isTrue();
        assertThat(pendingCount(workspace)).isEqualTo(2);

        // one member_add per ADDED, marked as a bulk invitation, and one
        // invitation_create per INVITED that names neither the address nor the 학번
        Long adds = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs where action = 'workspace.member_add' and target_id = ?
                   and detail ->> 'viaInvitation' = 'false' and detail ->> 'bulk' = 'true'
                """, Long.class, workspace.toString());
        assertThat(adds).isEqualTo(3);
        List<String> creates = jdbcTemplate.queryForList("""
                select detail::text from audit_logs where action = 'workspace.invitation_create' and target_id = ?
                """, String.class, workspace.toString());
        assertThat(creates).hasSize(2);
        assertThat(creates).allSatisfy(detail -> assertThat(detail)
                .doesNotContain(unknownEmail).doesNotContain(unknownStudentNo).contains("invitationId"));
    }

    @Test
    void anAccountThatIsNotActiveAnswersExactlyLikeAMissingOne() throws Exception {
        UUID workspace = createWorkspace();
        User pending = user("pending", UserStatus.PENDING_VERIFICATION);
        User disabled = user("disabled", UserStatus.DISABLED);
        User pendingStudent = user("pendingstudent", UserStatus.PENDING_VERIFICATION);
        pendingStudent.setProfile(UserPosition.STUDENT_UNDERGRAD, studentNo(), null, null);
        pendingStudent = userRepository.saveAndFlush(pendingStudent);

        invite(workspace, List.of(
                Map.of("email", pending.getEmail()),
                Map.of("email", disabled.getEmail()),
                Map.of("studentNo", pendingStudent.getStudentNo()),
                Map.of("email", email("missing"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("INVITED"))
                .andExpect(jsonPath("$.results[0].userId").doesNotExist())
                .andExpect(jsonPath("$.results[1].outcome").value("INVITED"))
                .andExpect(jsonPath("$.results[1].userId").doesNotExist())
                .andExpect(jsonPath("$.results[2].outcome").value("INVITED"))
                .andExpect(jsonPath("$.results[3].outcome").value("INVITED"));
        assertThat(isMember(workspace, pending)).isFalse();
        assertThat(isMember(workspace, disabled)).isFalse();
    }

    // ---------------------------------------------------------- validation

    @Test
    void theRequestIsRefusedWholeWhenAnEntryIsMalformed() throws Exception {
        UUID workspace = createWorkspace();

        List<Map<String, String>> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(Map.of("email", "bulk" + i + "." + run + "@pusan.ac.kr"));
        }
        invite(workspace, tooMany)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        invite(workspace, List.of())
                .andExpect(status().isUnprocessableContent());

        invite(workspace, List.of(
                Map.of("email", email("fine")),
                Map.of("email", email("both"), "studentNo", studentNo()),
                Map.of(),
                Map.of("email", "", "studentNo", " ")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("entries[1]"))
                .andExpect(jsonPath("$.errors[1].field").value("entries[2]"))
                .andExpect(jsonPath("$.errors[2].field").value("entries[3]"));

        invite(workspace, List.of(Map.of("studentNo", "12")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("entries[0].studentNo"));
        invite(workspace, List.of(Map.of("email", "not-an-address")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("entries[0].email"));

        // nothing from a refused request was applied
        assertThat(pendingCount(workspace)).isZero();
    }

    // -------------------------------------------------------- authorization

    @Test
    void onlyAnOwnerOfANonPersonalWorkspaceManagesInvitations() throws Exception {
        UUID workspace = createWorkspace();
        User member = activeUser("plainmember");
        invite(workspace, List.of(Map.of("email", member.getEmail()))).andExpect(status().isOk());
        String memberToken = jwtService.createAccessToken(member);
        String invitationId = invitedId(workspace, email("target"));

        invite(memberToken, workspace, List.of(Map.of("email", email("x"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));
        mockMvc.perform(get(invitations(workspace)).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));
        mockMvc.perform(delete(invitations(workspace) + "/" + invitationId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));

        personalWorkspaceService.ensurePersonalWorkspace(owner);
        UUID personal = jdbcTemplate.queryForObject("""
                select w.public_id from workspaces w join workspace_members m on m.workspace_id = w.id
                 where m.user_id = ? and w.kind = 'PERSONAL'
                """, UUID.class, owner.getId());
        invite(personal, List.of(Map.of("email", email("y"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_MANAGE_FORBIDDEN"));

        mockMvc.perform(get(invitations(SeedFixtures.UNKNOWN_ID)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------- rate limits

    @Test
    void callsArePacedPerMinute() throws Exception {
        UUID workspace = createWorkspace();
        for (int i = 0; i < WorkspaceInvitationService.INVITE_CALLS_PER_MINUTE; i++) {
            invite(workspace, List.of(Map.of("email", email("pace" + i)))).andExpect(status().isOk());
        }
        invite(workspace, List.of(Map.of("email", email("pace-over"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void entriesAreBudgetedPerHourAndARefusedRequestSpendsNothing() throws Exception {
        UUID workspace = createWorkspace();
        invite(workspace, bulk("budget-a", 200)).andExpect(status().isOk());
        invite(workspace, bulk("budget-b", 200)).andExpect(status().isOk());

        // 400 spent: 101 more does not fit, and asking must not spend the 100 left
        invite(workspace, bulk("budget-c", 101))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(pendingCount(workspace)).isEqualTo(400);

        invite(workspace, bulk("budget-d", 100)).andExpect(status().isOk());
        assertThat(pendingCount(workspace)).isEqualTo(500);
        invite(workspace, bulk("budget-e", 1)).andExpect(status().isTooManyRequests());
    }

    // ------------------------------------------------------- list and cancel

    @Test
    void theListShowsOnlyOpenInvitationsAndCancelClosesOne() throws Exception {
        UUID workspace = createWorkspace();
        String first = email("first");
        String second = studentNo();
        String accepted = email("accepted");
        invite(workspace, List.of(Map.of("email", first), Map.of("studentNo", second), Map.of("email", accepted)))
                .andExpect(status().isOk());
        User acceptor = user("accepted", UserStatus.ACTIVE);
        invitationClaimService.claimByEmail(acceptor);

        mockMvc.perform(get(invitations(workspace)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].email").value(first))
                .andExpect(jsonPath("$[0].studentNo").doesNotExist())
                .andExpect(jsonPath("$[0].role").value("MEMBER"))
                .andExpect(jsonPath("$[0].invitedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].invitedBy.id").value(owner.getPublicId().toString()))
                .andExpect(jsonPath("$[0].invitedBy.name").value(owner.getName()))
                .andExpect(jsonPath("$[1].studentNo").value(second));

        String firstId = invitedId(workspace, first);
        mockMvc.perform(delete(invitations(workspace) + "/" + firstId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        // canceled is no longer an open invitation: a second cancel is 404
        mockMvc.perform(delete(invitations(workspace) + "/" + firstId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_NOT_FOUND"));
        // so is an accepted one, an unknown id, and one from another workspace
        String acceptedId = jdbcTemplate.queryForObject(
                "select public_id::text from workspace_invitations where lower(invitee_email) = lower(?)",
                String.class, accepted);
        mockMvc.perform(delete(invitations(workspace) + "/" + acceptedId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(invitations(workspace) + "/" + SeedFixtures.UNKNOWN_ID)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        UUID other = createWorkspace();
        mockMvc.perform(delete(invitations(other) + "/" + invitedId(workspace, second.toLowerCase()))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(invitations(workspace)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.length()").value(1));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, canceled_by, canceled_at from workspace_invitations where public_id = ?::uuid",
                firstId);
        assertThat(row.get("status")).isEqualTo("CANCELED");
        assertThat(row.get("canceled_by")).isEqualTo(owner.getId());
        assertThat(row.get("canceled_at")).isNotNull();

        // a canceled invitation claims nothing
        invitationClaimService.claimByEmail(user("first", UserStatus.ACTIVE));
        assertThat(pendingCount(workspace)).isEqualTo(1);
        Long cancels = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'workspace.invitation_cancel' and target_id = ?",
                Long.class, workspace.toString());
        assertThat(cancels).isEqualTo(1);
    }

    // ----------------------------------------------------------------- claim

    @Test
    void emailVerificationClaimsEveryInvitationForTheAddress() throws Exception {
        UUID first = createWorkspace();
        UUID second = createWorkspace();
        String address = email("verify");
        invite(first, List.of(Map.of("email", address))).andExpect(status().isOk());
        invite(second, List.of(Map.of("email", address.toUpperCase()))).andExpect(status().isOk());
        User pending = user("verify", UserStatus.PENDING_VERIFICATION);

        jdbcTemplate.update("delete from auth_rate_limits where scope = 'verify:ip'");
        String rawToken = "inv-verify-" + run;
        jdbcTemplate.update("""
                insert into email_verifications (user_id, token_hash, purpose, expires_at)
                values (?, ?, 'SIGNUP', now() + interval '1 hour')
                """, pending.getId(), TokenHasher.sha256Hex(rawToken));
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", rawToken))))
                .andExpect(status().isOk());

        assertThat(isMember(first, pending)).isTrue();
        assertThat(isMember(second, pending)).isTrue();
        assertThat(pendingCount(first)).isZero();
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select i.status, i.accepted_user_id, i.accepted_at from workspace_invitations i
                  join workspaces w on w.id = i.workspace_id where w.public_id = ?
                """, first);
        assertThat(row.get("status")).isEqualTo("ACCEPTED");
        assertThat(row.get("accepted_user_id")).isEqualTo(pending.getId());
        assertThat(row.get("accepted_at")).isNotNull();
        Long viaInvitation = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs where action = 'workspace.member_add' and actor_id = ?
                   and detail ->> 'viaInvitation' = 'true' and detail ->> 'invitationId' is not null
                """, Long.class, pending.getId());
        assertThat(viaInvitation).isEqualTo(2);
    }

    @Test
    void theFirstStudentNumberSavedOnTheProfileClaimsItsInvitations() throws Exception {
        UUID workspace = createWorkspace();
        String number = studentNo();
        invite(workspace, List.of(Map.of("studentNo", number.toLowerCase()))).andExpect(status().isOk());
        User holder = activeUser("profile");

        mockMvc.perform(put("/api/v1/me/profile")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(holder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "position", "STUDENT_UNDERGRAD", "studentNo", number.toUpperCase(),
                                "departmentCode", "COMPUTER_SCIENCE"))))
                .andExpect(status().isOk())
                // the profile the write answers with already lists the workspace
                .andExpect(jsonPath("$.memberships[?(@.workspaceId == '%s')]".formatted(workspace)).exists());

        assertThat(isMember(workspace, holder)).isTrue();
        assertThat(pendingCount(workspace)).isZero();
    }

    @Test
    void anAdministratorSettingAStudentNumberClaimsItsInvitations() throws Exception {
        UUID workspace = createWorkspace();
        String number = studentNo();
        invite(workspace, List.of(Map.of("studentNo", number))).andExpect(status().isOk());
        User holder = activeUser("admin-set");
        String adminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());

        mockMvc.perform(patch("/api/v1/admin/users/" + holder.getPublicId() + "/profile")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "position", "STUDENT_GRADUATE", "studentNo", number, "reason", "학번 등록"))))
                .andExpect(status().isOk());

        assertThat(isMember(workspace, holder)).isTrue();
        assertThat(pendingCount(workspace)).isZero();
    }

    @Test
    void aClaimIsIdempotentAndSatisfiedByAnExistingMembership() throws Exception {
        UUID workspace = createWorkspace();
        User person = user("idem", UserStatus.PENDING_VERIFICATION);
        invite(workspace, List.of(Map.of("email", person.getEmail()))).andExpect(status().isOk());
        // already a member by another route when the claim runs
        person.setStatus(UserStatus.ACTIVE);
        person = userRepository.saveAndFlush(person);
        invite(workspace, List.of(Map.of("email", person.getEmail())))
                .andExpect(jsonPath("$.results[0].outcome").value("ADDED"));

        invitationClaimService.claimByEmail(person);
        invitationClaimService.claimByEmail(person);

        assertThat(pendingCount(workspace)).isZero();
        Long rows = jdbcTemplate.queryForObject("""
                select count(*) from workspace_members m join workspaces w on w.id = m.workspace_id
                 where w.public_id = ? and m.user_id = ?
                """, Long.class, workspace, person.getId());
        assertThat(rows).isEqualTo(1);
        // the invitation is satisfied, but the claim created no membership, so no claim audit
        Long viaInvitation = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs where action = 'workspace.member_add' and actor_id = ?
                   and detail ->> 'viaInvitation' = 'true'
                """, Long.class, person.getId());
        assertThat(viaInvitation).isZero();
        Long accepted = jdbcTemplate.queryForObject("""
                select count(*) from workspace_invitations where accepted_user_id = ? and status = 'ACCEPTED'
                """, Long.class, person.getId());
        assertThat(accepted).isEqualTo(1);
    }

    @Test
    void aClaimSkipsADeletedWorkspace() throws Exception {
        UUID workspace = createWorkspace();
        String address = email("deleted");
        invite(workspace, List.of(Map.of("email", address))).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/workspaces/" + workspace).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        User person = user("deleted", UserStatus.ACTIVE);
        invitationClaimService.claimByEmail(person);

        assertThat(isMember(workspace, person)).isFalse();
        String state = jdbcTemplate.queryForObject(
                "select status from workspace_invitations where lower(invitee_email) = lower(?)",
                String.class, address);
        assertThat(state).isEqualTo("PENDING");
    }

    @Test
    void removingAMemberDoesNotReopenTheInvitationTheyAccepted() throws Exception {
        UUID workspace = createWorkspace();
        String address = email("removed");
        invite(workspace, List.of(Map.of("email", address))).andExpect(status().isOk());
        User person = user("removed", UserStatus.ACTIVE);
        invitationClaimService.claimByEmail(person);
        assertThat(isMember(workspace, person)).isTrue();

        mockMvc.perform(delete("/api/v1/workspaces/" + workspace + "/members/" + person.getPublicId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        invitationClaimService.claimByEmail(person);

        assertThat(isMember(workspace, person)).isFalse();
        assertThat(pendingCount(workspace)).isZero();
    }

    // --------------------------------------------------------------- helpers

    private ResultActions invite(UUID workspace, List<? extends Map<String, ?>> entries) throws Exception {
        return invite(ownerToken, workspace, entries);
    }

    private ResultActions invite(String token, UUID workspace, List<? extends Map<String, ?>> entries)
            throws Exception {
        return mockMvc.perform(post(invitations(workspace))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("entries", entries))));
    }

    private List<Map<String, String>> bulk(String label, int count) {
        List<Map<String, String>> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(Map.of("email", label + "-" + i + "." + run + "@pusan.ac.kr"));
        }
        return entries;
    }

    /** Invites one address and returns the open invitation's id. */
    private String invitedId(UUID workspace, String identifier) throws Exception {
        String body = invite(workspace, List.of(identifier.contains("@")
                ? Map.of("email", identifier) : Map.of("studentNo", identifier)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode result = objectMapper.readTree(body).get("results").get(0);
        return result.get("invitationId").asString();
    }

    private static String invitations(UUID workspace) {
        return "/api/v1/workspaces/" + workspace + "/invitations";
    }

    private UUID createWorkspace() throws Exception {
        String body = mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "COURSE", "name", "초대 테스트 " + SEQ.incrementAndGet()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private boolean isMember(UUID workspace, User user) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*) from workspace_members m join workspaces w on w.id = m.workspace_id
                 where w.public_id = ? and m.user_id = ?
                """, Long.class, workspace, user.getId());
        return count != null && count > 0;
    }

    private int pendingCount(UUID workspace) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)::int from workspace_invitations i join workspaces w on w.id = i.workspace_id
                 where w.public_id = ? and i.status = 'PENDING'
                """, Integer.class, workspace);
        return count == null ? 0 : count;
    }

    private String email(String label) {
        return "inv." + label + "." + run + "@pusan.ac.kr";
    }

    private String studentNo() {
        return "iv" + run.substring(0, 6) + SEQ.incrementAndGet();
    }

    private User activeUser(String label) {
        return user(label, UserStatus.ACTIVE);
    }

    private User activeStudent(String label) {
        User user = activeUser(label);
        user.setProfile(UserPosition.STUDENT_UNDERGRAD, studentNo(), null, null);
        return userRepository.saveAndFlush(user);
    }

    private User user(String label, UserStatus status) {
        String address = email(label);
        return userRepository.findByEmail(address).orElseGet(() -> {
            User user = new User(address, "{test-no-login}", "초대 " + label);
            user.setStatus(status);
            if (status == UserStatus.ACTIVE) {
                user.setEmailVerifiedAt(Instant.now());
            }
            return userRepository.saveAndFlush(user);
        });
    }
}
