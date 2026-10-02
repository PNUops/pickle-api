package kr.ac.pusan.pickle.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Mail to administrators (V133): who in an organisation is mailed about a new
 * request, and how the dispatcher holds admin mail so a burst of requests costs
 * each recipient one mail at once and one summary when the window closes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminMailBundlingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationDispatchJob dispatchJob;

    @Autowired
    private MockMailSender mockMailSender;

    /** A requester id that matches no account, for the cases that are not about exclusion. */
    private static final long NOBODY = -1L;

    private Org org;
    private Org otherOrg;
    private User adminA;
    private User adminB;
    private User manager;
    private User viewer;

    @BeforeEach
    void setUp() {
        mockMailSender.clear();
        org = ensureOrg("메일 담당 기관");
        otherOrg = ensureOrg("메일 담당 다른 기관");
        adminA = ensureUser("amb.admin.a@pusan.ac.kr", "담당기관관리자A", UserRole.ORG_ADMIN);
        adminB = ensureUser("amb.admin.b@pusan.ac.kr", "담당기관관리자B", UserRole.ORG_ADMIN);
        manager = ensureUser("amb.manager@pusan.ac.kr", "담당기관운영자", UserRole.ORG_MANAGER);
        viewer = ensureUser("amb.viewer@pusan.ac.kr", "담당기관열람자", UserRole.ORG_VIEWER);
        for (User user : List.of(adminA, adminB, manager, viewer)) {
            jdbcTemplate.update("update users set status = 'ACTIVE' where id = ?", user.getId());
        }
        jdbcTemplate.update("delete from user_org_roles where org_id = ?", org.getId());
        SeedFixtures.grantOrgRole(jdbcTemplate, adminA.getId(), org.getId(), UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbcTemplate, adminB.getId(), org.getId(), UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbcTemplate, manager.getId(), org.getId(), UserRole.ORG_MANAGER);
        SeedFixtures.grantOrgRole(jdbcTemplate, viewer.getId(), org.getId(), UserRole.ORG_VIEWER);

        jdbcTemplate.update("delete from notifications where user_id in (?, ?, ?, ?)",
                adminA.getId(), adminB.getId(), manager.getId(), viewer.getId());
        // Same reason as NotificationTest: the dispatcher takes a bounded batch,
        // so other classes' leftovers must not crowd out this class's rows.
        jdbcTemplate.update("update notifications set status = 'SENT', sent_at = now()"
                + " where status = 'PENDING'");
    }

    // ── who is mailed about a new request ─────────────────────────────────

    @Test
    void withNobodyChosenEveryOrgAdminIsMailed() {
        assertThat(notificationService.requestMailRecipientIds(org.getId(), NOBODY))
                .containsExactlyInAnyOrder(adminA.getId(), adminB.getId());
    }

    @Test
    void onceSomebodyIsChosenOnlyTheChosenAreMailed() {
        choose(manager);

        assertThat(notificationService.requestMailRecipientIds(org.getId(), NOBODY))
                .containsExactly(manager.getId());
    }

    @Test
    void aChosenAccountThatIsNoLongerActiveFallsBackToTheAdmins() {
        choose(manager);
        jdbcTemplate.update("update users set status = 'DISABLED' where id = ?", manager.getId());

        assertThat(notificationService.requestMailRecipientIds(org.getId(), NOBODY))
                .containsExactlyInAnyOrder(adminA.getId(), adminB.getId());
    }

    @Test
    void theRequesterIsNeverTheirOwnRecipientAndTheOthersStepIn() {
        choose(manager);

        // the only one chosen filed the request: the other admins hear of it
        assertThat(notificationService.requestMailRecipientIds(org.getId(), manager.getId()))
                .containsExactlyInAnyOrder(adminA.getId(), adminB.getId());
        // an admin filing with nobody chosen is left out of the fallback
        jdbcTemplate.update("update user_org_roles set request_mail = false where org_id = ?",
                org.getId());
        assertThat(notificationService.requestMailRecipientIds(org.getId(), adminA.getId()))
                .containsExactly(adminB.getId());
    }

    @Test
    void theChoiceIsWrittenAloneAndNeverRewritesTheRole() throws Exception {
        // a concurrent grant may change the role between our read and write;
        // the toggle must not carry the role it read back into the row
        putRequestMail(manager, org, token(adminA), true).andExpect(status().isOk());
        jdbcTemplate.update("update user_org_roles set role = 'ORG_VIEWER', request_mail = false"
                + " where user_id = ? and org_id = ?", manager.getId(), org.getId());

        putRequestMail(manager, org, token(adminA), true)
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbcTemplate.queryForObject(
                "select role::text from user_org_roles where user_id = ? and org_id = ?",
                String.class, manager.getId(), org.getId())).isEqualTo("ORG_VIEWER");
    }

    @Test
    void anOrgAdminChoosesItselfAndTheResponseSaysSo() throws Exception {
        putRequestMail(adminA, org, token(adminA), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(org.getPublicId().toString()))
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"))
                .andExpect(jsonPath("$.requestMail").value(true));
        assertThat(notificationService.requestMailRecipientIds(org.getId(), NOBODY))
                .containsExactly(adminA.getId());

        putRequestMail(adminA, org, token(adminA), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestMail").value(false));
        assertThat(notificationService.requestMailRecipientIds(org.getId(), NOBODY))
                .containsExactlyInAnyOrder(adminA.getId(), adminB.getId());
    }

    @Test
    void aRoleThatCannotApproveCannotBeChosen() throws Exception {
        putRequestMail(viewer, org, token(adminA), true)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("enabled"));
        // turning it off is always allowed, and a no-op here
        putRequestMail(viewer, org, token(adminA), false)
                .andExpect(status().isOk());
    }

    @Test
    void anOrgAdminCannotChooseInAnOrgItDoesNotAdminister() throws Exception {
        SeedFixtures.grantOrgRole(jdbcTemplate, manager.getId(), otherOrg.getId(),
                UserRole.ORG_MANAGER);

        putRequestMail(manager, otherOrg, token(adminA), true)
                .andExpect(status().isNotFound());
        // and an account without a role in the org is not there to choose
        User outsider = ensureUser("amb.outsider@pusan.ac.kr", "역할없음", UserRole.USER);
        putRequestMail(outsider, org, token(adminA), true)
                .andExpect(status().isNotFound());
        // a manager is not an admin of the org and cannot choose anyone
        putRequestMail(manager, org, token(manager), true)
                .andExpect(status().isForbidden());
    }

    @Test
    void demotingAChosenAccountToViewerDropsTheChoice() throws Exception {
        choose(manager);
        String sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());

        mockMvc.perform(put("/api/v1/admin/users/" + manager.getPublicId() + "/org-roles/"
                        + org.getPublicId())
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ORG_VIEWER\",\"expectedRevision\":" + jdbcTemplate.queryForObject("select admin_revision from orgs where id = ?", Long.class, org.getId()) + "}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select request_mail from user_org_roles where user_id = ? and org_id = ?",
                Boolean.class, manager.getId(), org.getId())).isFalse();
        assertThat(notificationService.requestMailRecipientIds(org.getId(), NOBODY))
                .containsExactlyInAnyOrder(adminA.getId(), adminB.getId());
    }

    // ── how admin mail is held and sent together ──────────────────────────

    @Test
    void theFirstAdminMailGoesAtOnceAndTheRestWaitForOneSummary() {
        submitted(adminA, "첫 워크스페이스");
        dispatchJob.dispatch();
        assertThat(mailsTo(adminA)).hasSize(1);
        assertThat(mailsTo(adminA).getFirst().subject()).isEqualTo("[Pickle] 새 VM 신청 접수");

        // three more inside the window: nothing leaves, the inbox has them all
        submitted(adminA, "둘째 워크스페이스");
        submitted(adminA, "셋째 워크스페이스");
        submitted(adminA, "넷째 워크스페이스");
        dispatchJob.dispatch();
        assertThat(mailsTo(adminA)).hasSize(1);
        assertThat(countByStatus(adminA, "PENDING")).isEqualTo(3);

        closeWindow(adminA);
        dispatchJob.dispatch();

        List<MailMessage> mails = mailsTo(adminA);
        assertThat(mails).hasSize(2);
        MailMessage summary = mails.getLast();
        assertThat(summary.subject()).isEqualTo("[Pickle] 관리자 알림 3건");
        // one line for the three, not three lines
        assertThat(summary.textBody())
                .contains("- 새 VM 신청 접수 3건")
                .contains("https://pickle.pusan.ac.kr/admin/requests\n");
        assertThat(summary.htmlBody())
                .contains("신청 확인하기")
                .contains("https://pickle.pusan.ac.kr/admin/requests");
        // the single anchor rule holds for the summary as well
        assertThat(summary.htmlBody().split("<a ", -1)).hasSize(2);
        assertThat(countByStatus(adminA, "SENT")).isEqualTo(4);

        // and the next one waits again, measured from the summary
        submitted(adminA, "다섯째 워크스페이스");
        dispatchJob.dispatch();
        assertThat(mailsTo(adminA)).hasSize(2);
    }

    @Test
    void oneWaitingRowAfterTheWindowIsSentAsItself() {
        submitted(adminA, "처음");
        dispatchJob.dispatch();
        submitted(adminA, "나중");
        closeWindow(adminA);

        dispatchJob.dispatch();

        MailMessage mail = mailsTo(adminA).getLast();
        assertThat(mail.subject()).isEqualTo("[Pickle] 새 VM 신청 접수");
        assertThat(mail.textBody()).contains("워크스페이스 '나중'");
    }

    @Test
    void mailToTheRequesterAndUrgentAdminMailAreNeverHeld() {
        submitted(adminA, "창 열기");
        dispatchJob.dispatch();
        assertThat(mailsTo(adminA)).hasSize(1);

        // the same account, told as a requester: not held
        notificationService.publish(adminA.getId(), NotificationEvent.REQUEST_SUBMITTED,
                Map.of("requestId", UUID.randomUUID(), "workspaceName", "본인 신청",
                        "purpose", "목적", "type", "VM"), null);
        // an urgent admin notice: not held either
        notificationService.publishToAdmins(List.of(adminA.getId()),
                NotificationEvent.CERT_FAILURE, Map.of("fqdn", "x.example.test", "reason", "시험"),
                null);
        assertThat(jdbcTemplate.queryForList(
                "select bundle from notifications where user_id = ? and status = 'PENDING'",
                Boolean.class, adminA.getId())).containsOnly(false);

        dispatchJob.dispatch();

        assertThat(mailsTo(adminA)).hasSize(3);
    }

    @Test
    void aMixedSummaryPointsAtTheInbox() {
        submitted(adminA, "섞기 시작");
        dispatchJob.dispatch();
        submitted(adminA, "섞인 신청");
        notificationService.publishToAdmins(List.of(adminA.getId()),
                NotificationEvent.VM_DELETE_COMPLETED,
                Map.of("vmId", UUID.randomUUID(), "vmName", "지워진\nVM"), null);
        closeWindow(adminA);

        dispatchJob.dispatch();

        MailMessage summary = mailsTo(adminA).getLast();
        assertThat(summary.subject()).isEqualTo("[Pickle] 관리자 알림 2건");
        assertThat(summary.textBody())
                .contains("https://pickle.pusan.ac.kr/console/notifications")
                // a name with a newline stays on its own list line
                .doesNotContain("지워진\nVM");
        assertThat(summary.htmlBody()).contains("알림 확인하기");
    }

    @Test
    void aFailedSummaryBacksOffEveryRowItCarried() {
        User failing = ensureUser("amb.broken+fail@pusan.ac.kr", "묶음실패", UserRole.ORG_ADMIN);
        jdbcTemplate.update("update users set status = 'ACTIVE' where id = ?", failing.getId());
        jdbcTemplate.update("delete from notifications where user_id = ?", failing.getId());
        submitted(failing, "실패 하나");
        submitted(failing, "실패 둘");

        dispatchJob.dispatch();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select status::text as status, attempts, next_attempt_at > now() as later
                  from notifications where user_id = ?
                """, failing.getId());
        assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row)
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 1)
                .containsEntry("later", true));
    }

    @Test
    void aResentRowIsNoLongerHeld() {
        submitted(adminA, "재발송");
        jdbcTemplate.update("update notifications set status = 'FAILED' where user_id = ?",
                adminA.getId());
        // what the delivery log's resend writes
        jdbcTemplate.update("""
                update notifications
                   set status = 'PENDING', next_attempt_at = now(), bundle = false
                 where user_id = ? and status = 'FAILED'
                """, adminA.getId());
        // an admin mail just went out, so a held row would wait
        submitted(adminB, "다른 사람");
        jdbcTemplate.update("""
                insert into notifications (user_id, event, title, body, status, bundle, sent_at)
                values (?, 'request.submitted', '이미 보냄', '본문', 'SENT', true, now())
                """, adminA.getId());

        dispatchJob.dispatch();

        assertThat(mailsTo(adminA)).hasSize(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void submitted(User admin, String workspaceName) {
        notificationService.publishToAdmins(List.of(admin.getId()),
                NotificationEvent.REQUEST_SUBMITTED,
                Map.of("requestId", UUID.randomUUID(), "workspaceName", workspaceName,
                        "purpose", "수업 실습", "type", "VM", "admin", true),
                null);
    }

    /** Moves the recipient's last admin mail back past the 60-minute window. */
    private void closeWindow(User user) {
        jdbcTemplate.update("""
                update notifications set sent_at = sent_at - interval '61 minutes'
                 where user_id = ? and bundle and sent_at is not null
                """, user.getId());
    }

    private void choose(User user) {
        jdbcTemplate.update(
                "update user_org_roles set request_mail = true where user_id = ? and org_id = ?",
                user.getId(), org.getId());
    }

    private long countByStatus(User user, String statusName) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications where user_id = ? and status::text = ?",
                Long.class, user.getId(), statusName);
    }

    private List<MailMessage> mailsTo(User user) {
        return mockMailSender.getMessages().stream()
                .filter(mail -> mail.to().equalsIgnoreCase(user.getEmail()))
                .toList();
    }

    private ResultActions putRequestMail(User user, Org target, String token, boolean enabled)
            throws Exception {
        return mockMvc.perform(put("/api/v1/admin/users/" + user.getPublicId() + "/org-roles/"
                        + target.getPublicId() + "/request-mail")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + ",\"expectedRevision\":" + jdbcTemplate.queryForObject("select admin_revision from orgs where id = ?", Long.class, target.getId()) + "}"));
    }

    private String token(User user) {
        return jwtService.createAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }

    private Org ensureOrg(String name) {
        return orgRepository.findFirstByNameOrderByIdAsc(name)
                .orElseGet(() -> orgRepository.save(new Org(name, null)));
    }

    private User ensureUser(String email, String name, UserRole role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
