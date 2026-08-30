package kr.ac.pusan.pickle.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
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

/**
 * Notification core per contract v0.5.0: the self-scoped inbox (list /
 * unread-count / read / read-all with 404 masking and idempotent reads),
 * per-user dedup on publish, and the email dispatcher's
 * success / backoff / FAILED-after-3 lifecycle (run directly — the @Recurring
 * schedule is too slow for tests; the CAS claim makes that safe).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class NotificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationDispatchJob dispatchJob;

    @Autowired
    private MockMailSender mockMailSender;

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        mockMailSender.clear();
        alice = ensureUser("notif.alice@pusan.ac.kr", "알림앨리스");
        bob = ensureUser("notif.bob@pusan.ac.kr", "알림밥");
        aliceToken = jwtService.createAccessToken(alice);
        bobToken = jwtService.createAccessToken(bob);
        // isolate the inbox assertions from rows other tests fan out
        jdbcTemplate.update("delete from notifications where user_id in (?, ?)",
                alice.getId(), bob.getId());
        // ...and clear the queue ahead of them. The dispatcher takes the hundred
        // oldest due rows, so once other classes have left that many pending,
        // this class's own row never gets claimed and the dispatch assertions
        // fail for a reason that has nothing to do with dispatching. Retiring
        // them is safe: no test asserts on another class's pending rows.
        jdbcTemplate.update(
                "update notifications set status = 'SENT', sent_at = now()"
                        + " where status = 'PENDING'");
    }

    @Test
    void inboxIsSelfScopedWithIdempotentReadReceipts() throws Exception {
        publishAnnouncement(alice.getId(), "첫 번째 알림", null);
        publishAnnouncement(alice.getId(), "두 번째 알림", null);
        publishAnnouncement(bob.getId(), "밥의 알림", null);

        // list: own rows only, newest first, contract shape
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].title").value("두 번째 알림"))
                .andExpect(jsonPath("$.content[0].event").value("announcement"))
                .andExpect(jsonPath("$.content[0].importance").value("NORMAL"))
                .andExpect(jsonPath("$.content[0].readAt").value((Object) null));
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));

        long aliceFirstId = jdbcTemplate.queryForObject("""
                select min(id) from notifications where user_id = ?
                """, Long.class, alice.getId());
        long bobId = jdbcTemplate.queryForObject("""
                select max(id) from notifications where user_id = ?
                """, Long.class, bob.getId());

        // another user's row → 404 (masked), and bob still sees his own
        mockMvc.perform(post("/api/v1/notifications/" + pub("notifications", bobId) + "/read")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // idempotent read: the first readAt wins, the re-read still answers 200
        String firstRead = mockMvc.perform(post("/api/v1/notifications/" + pub("notifications", aliceFirstId) + "/read")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String secondRead = mockMvc.perform(post("/api/v1/notifications/" + pub("notifications", aliceFirstId) + "/read")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(secondRead).isEqualTo(firstRead);

        // unreadOnly filters the read row out
        mockMvc.perform(get("/api/v1/notifications?unreadOnly=true")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // read-all reports the number it flipped; a second call flips nothing
        mockMvc.perform(post("/api/v1/notifications/read-all")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));
        mockMvc.perform(post("/api/v1/notifications/read-all")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(0));
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        // unauthenticated → 401
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void duplicatePublishWithDedupKeyIsANoOp() {
        String dedup = "test_dedup:" + UUID.randomUUID();
        publishAnnouncement(alice.getId(), "중복 방지", dedup);
        publishAnnouncement(alice.getId(), "중복 방지", dedup);
        Long rows = jdbcTemplate.queryForObject("""
                select count(*) from notifications where user_id = ? and dedup_key = ?
                """, Long.class, alice.getId(), dedup);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void dispatcherSendsPendingRowsAndRecordsSent() {
        publishAnnouncement(alice.getId(), "발송 확인", null);

        dispatchJob.dispatch();

        MailMessage mail = mockMailSender.lastMessageTo(alice.getEmail());
        assertThat(mail).isNotNull();
        assertThat(mail.subject()).isEqualTo("[Pickle] 발송 확인");
        assertThat(mail.textBody())
                .contains("Pickle 운영팀")
                .contains("부산대학교 클라우드 플랫폼")
                .contains("운영: 부산대학교 정보컴퓨터공학부 PNUops");
        // the branded HTML part rides along; the text part above is the fallback
        assertThat(mail.htmlBody()).isNotNull()
                .contains("<!DOCTYPE html>")
                .contains("발송 확인")
                .contains("max-width:600px");
        assertThat(jdbcTemplate.queryForMap("""
                select status::text as status, attempts, sent_at from notifications
                 where user_id = ? order by id desc limit 1
                """, alice.getId()))
                .containsEntry("status", "SENT")
                .containsEntry("attempts", 1)
                .hasEntrySatisfying("sent_at", sentAt -> assertThat(sentAt).isNotNull());
    }

    @Test
    void dispatcherLinksBackToTheConsoleOnlyWhenTheRowCarriesAPath() {
        // an announcement has no link_path; a VM notice does
        publishAnnouncement(alice.getId(), "링크 없는 알림", null);
        dispatchJob.dispatch();
        assertThat(mockMailSender.lastMessageTo(alice.getEmail()).htmlBody())
                .doesNotContain("<a ");

        String vmPublicId = UUID.randomUUID().toString();
        notificationService.publish(alice.getId(), NotificationEvent.VM_EXPIRY_NOTICE,
                Map.of("vmId", vmPublicId, "vmName", "vm-link", "endDate", "2026-09-01",
                        "days", 7, "daysLeft", 2L),
                null);

        dispatchJob.dispatch();

        assertThat(mockMailSender.lastMessageTo(alice.getEmail()).htmlBody())
                .contains("콘솔에서 확인")
                .contains("https://pickle.pusan.ac.kr/console/vms/" + vmPublicId);
    }

    @Test
    void dispatcherBacksOffThenParksFailedAfterThreeAttempts() {
        User failing = ensureUser("notif.broken+fail@pusan.ac.kr", "발송실패");
        jdbcTemplate.update("delete from notifications where user_id = ?", failing.getId());
        publishAnnouncement(failing.getId(), "실패 알림", null);

        // attempt 1: failure → still PENDING, backoff pushed into the future
        dispatchJob.dispatch();
        Map<String, Object> row = failingRow(failing.getId());
        assertThat(row).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant())
                .isAfter(java.time.Instant.now());
        assertThat((String) row.get("last_error")).contains("모의 SMTP 실패");

        // attempt 2: due again → still PENDING
        forceDue(failing.getId());
        dispatchJob.dispatch();
        assertThat(failingRow(failing.getId()))
                .containsEntry("status", "PENDING").containsEntry("attempts", 2);

        // attempt 3: parks FAILED with the error kept
        forceDue(failing.getId());
        dispatchJob.dispatch();
        row = failingRow(failing.getId());
        assertThat(row).containsEntry("status", "FAILED").containsEntry("attempts", 3);
        assertThat((String) row.get("last_error")).isNotBlank();

        // a FAILED row is never picked up again
        forceDue(failing.getId());
        dispatchJob.dispatch();
        assertThat(failingRow(failing.getId())).containsEntry("attempts", 3);

        // resend (admin delivery log: CAS FAILED→PENDING, due now) is a
        // SINGLE shot: attempts are already ≥ the budget, so one more failed
        // try parks the row FAILED again immediately — no backoff loop
        jdbcTemplate.update("""
                update notifications set status = 'PENDING', next_attempt_at = now()
                 where user_id = ? and status = 'FAILED'
                """, failing.getId());
        dispatchJob.dispatch();
        row = failingRow(failing.getId());
        assertThat(row).containsEntry("status", "FAILED").containsEntry("attempts", 4);
        assertThat((String) row.get("last_error")).contains("모의 SMTP 실패");
        // and the re-parked row stays parked
        forceDue(failing.getId());
        dispatchJob.dispatch();
        assertThat(failingRow(failing.getId()))
                .containsEntry("status", "FAILED").containsEntry("attempts", 4);
    }

    @Test
    void dispatcherSkipsRecipientDeactivatedAfterEnqueue() {
        User leaver = ensureUser("notif.leaver@pusan.ac.kr", "탈퇴자");
        jdbcTemplate.update("delete from notifications where user_id = ?", leaver.getId());
        publishAnnouncement(leaver.getId(), "지연 발송 알림", null);
        // deactivated between enqueue and dispatch (비활성 계정 차단)
        jdbcTemplate.update("update users set status = 'DISABLED' where id = ?", leaver.getId());

        dispatchJob.dispatch();

        assertThat(mockMailSender.lastMessageTo(leaver.getEmail())).isNull();
        Map<String, Object> row = failingRow(leaver.getId());
        assertThat(row).containsEntry("status", "SKIPPED").containsEntry("attempts", 0);
        assertThat((String) row.get("last_error")).contains("비활성");

        // SKIPPED rows are terminal — never picked up again
        forceDue(leaver.getId());
        dispatchJob.dispatch();
        assertThat(failingRow(leaver.getId())).containsEntry("status", "SKIPPED");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void publishAnnouncement(long userId, String title, String dedupKey) {
        notificationService.publish(userId, NotificationEvent.ANNOUNCEMENT,
                Map.of("title", title, "body", title + " 본문"), dedupKey);
    }

    private Map<String, Object> failingRow(long userId) {
        return jdbcTemplate.queryForMap("""
                select status::text as status, attempts, next_attempt_at, last_error
                  from notifications
                 where user_id = ? order by id desc limit 1
                """, userId);
    }

    private void forceDue(long userId) {
        jdbcTemplate.update("""
                update notifications set next_attempt_at = now() - interval '1 second'
                 where user_id = ?
                """, userId);
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{noop}unused", name);
            user.setStatus(UserStatus.ACTIVE);
            return userRepository.save(user);
        });
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
