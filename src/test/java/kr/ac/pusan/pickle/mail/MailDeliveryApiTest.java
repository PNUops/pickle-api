package kr.ac.pusan.pickle.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.notification.NotificationRetentionSweeper;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MailDeliveryApiTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private MailDeliveryJournal journal;
    @Autowired private NotificationRetentionSweeper sweeper;
    @Autowired private MailDeliveryQueryService query;

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void onlySystemRolesCanReadDeliveryEvidence(UserRole role) throws Exception {
        var request = mvc.perform(get("/api/v1/admin/mail-deliveries").header("Authorization", token(user(role))));
        if (role.isSysTier()) request.andExpect(status().isOk());
        else request.andExpect(status().isForbidden());
    }

    @Test
    void legacyAddressAndAttemptDetailsStayUnknownAfterAccountChanges() throws Exception {
        User recipient = user(UserRole.USER);
        long id = notification(recipient, "SENT", null, false, 3);
        journal.importLegacyBatch(1000);
        UUID delivery = delivery(id);
        jdbc.update("update users set email = ? where id = ?", "new." + UUID.randomUUID() + "@pusan.ac.kr", recipient.getId());
        mvc.perform(get("/api/v1/admin/mail-deliveries/" + delivery).header("Authorization", token(user(UserRole.SYS_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.recipientEmail").isEmpty())
                .andExpect(jsonPath("$.delivery.currentUserEmail").isNotEmpty())
                .andExpect(jsonPath("$.delivery.legacyAddressUnknown").value(true))
                .andExpect(jsonPath("$.delivery.attempts").value(3))
                .andExpect(jsonPath("$.attemptHistory.length()").value(0));
    }

    @Test
    void inboxDeletionKeepsPermanentEvidenceAndDisablesResendWithoutContent() throws Exception {
        User recipient = user(UserRole.USER);
        long old = notification(recipient, "FAILED", "old@pickle.invalid", false, 3);
        UUID sourceId = jdbc.queryForObject("select public_id from notifications where id = ?", UUID.class, old);
        jdbc.update("update notifications set created_at = now() - interval '400 days' where id = ?", old);
        long pending = notification(recipient, "PENDING", "old@pickle.invalid", false, 0);
        long sending = notification(recipient, "SENDING", "old@pickle.invalid", false, 1);
        jdbc.update("update notifications set created_at = now() - interval '400 days' where id in (?, ?)", pending, sending);
        sweeper.sweep();
        assertThat(jdbc.queryForObject("select count(*) from notifications where id = ?", Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where id in (?, ?)", Integer.class, pending, sending)).isEqualTo(2);
        UUID permanent = jdbc.queryForObject("select public_id from mail_deliveries where notification_public_id = ?", UUID.class, sourceId);
        mvc.perform(get("/api/v1/admin/mail-deliveries/" + permanent).header("Authorization", token(user(UserRole.SYS_ADMIN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.status").value("FAILED"))
                .andExpect(jsonPath("$.delivery.recipientEmail").value("old@pickle.invalid"))
                .andExpect(jsonPath("$.delivery.canResend").value(false))
                .andExpect(jsonPath("$.delivery.cannotResendReason").value("CONTENT_EXPIRED"));
    }

    @Test
    void retryWaitAndBundleWaitAreDifferentFromAnOrdinaryQueue() throws Exception {
        User bundled = user(UserRole.USER);
        long sent = notification(bundled, "SENT", bundled.getEmail(), true, 1);
        jdbc.update("update notifications set sent_at = now() where id = ?", sent);
        long waiting = notification(bundled, "PENDING", bundled.getEmail(), true, 0);
        long retry = notification(user(UserRole.USER), "PENDING", "retry@pickle.invalid", false, 1);
        long ordinary = notification(user(UserRole.USER), "PENDING", "normal@pickle.invalid", false, 0);
        journal.importLegacyBatch(1000);
        var actor = token(user(UserRole.SYS_VIEWER));
        mvc.perform(get("/api/v1/admin/mail-deliveries/" + delivery(waiting)).header("Authorization", actor))
                .andExpect(jsonPath("$.delivery.queueState").value("BUNDLE_WAIT"));
        assertThat(query.detail(delivery(waiting)).delivery().nextAttemptAt())
                .isAfter(Instant.now().plusSeconds(59 * 60)).isBefore(Instant.now().plusSeconds(61 * 60));
        mvc.perform(get("/api/v1/admin/mail-deliveries/" + delivery(retry)).header("Authorization", actor))
                .andExpect(jsonPath("$.delivery.queueState").value("RETRY_WAIT"));
        mvc.perform(get("/api/v1/admin/mail-deliveries/" + delivery(ordinary)).header("Authorization", actor))
                .andExpect(jsonPath("$.delivery.queueState").value("NORMAL_WAIT"));
    }

    @Test
    void onlyAnOperationalSystemRoleCanResendKnownFailedNotification() throws Exception {
        long failed = notification(user(UserRole.USER), "FAILED", "frozen@pickle.invalid", false, 3);
        journal.importLegacyBatch(1000);
        UUID id = delivery(failed);
        jdbc.update("update notifications set last_error = 'SMTP_REJECTED' where id = ?", failed);
        jdbc.update("update mail_deliveries set failure_code = 'SMTP_REJECTED' where public_id = ?", id);
        mvc.perform(post("/api/v1/admin/mail-deliveries/" + id + "/resend")
                        .header("Authorization", token(user(UserRole.SYS_VIEWER))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/mail-deliveries/" + id + "/resend")
                        .header("Authorization", token(user(UserRole.SYS_MANAGER))))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select state from mail_deliveries where public_id = ?", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select attempts from notifications where id = ?", Integer.class, failed)).isEqualTo(3);
    }

    @Test
    void ambiguousAndAccountOutcomesCannotBeManuallyReplayed() throws Exception {
        User actor = user(UserRole.SYS_ADMIN);
        UUID account = journal.enqueueAccount("support@pickle.invalid", "account.password_reset", null, Instant.now().plusSeconds(300));
        UUID owner = UUID.randomUUID();
        journal.beginAccountAttempt(account, owner);
        journal.finishAccountAttempt(account, owner, "FAILED", "SMTP_REJECTED");
        mvc.perform(post("/api/v1/admin/mail-deliveries/" + account + "/resend").header("Authorization", token(actor)))
                .andExpect(status().isConflict());
        long ambiguous = notification(user(UserRole.USER), "UNKNOWN", "frozen@pickle.invalid", false, 1);
        journal.importLegacyBatch(1000);
        mvc.perform(post("/api/v1/admin/mail-deliveries/" + delivery(ambiguous) + "/resend").header("Authorization", token(actor)))
                .andExpect(status().isConflict());
    }

    @Test
    void legacyUnknownAddressIsNotReconstructedForAResend() throws Exception {
        long failed = notification(user(UserRole.USER), "FAILED", null, false, 3);
        journal.importLegacyBatch(1000);
        mvc.perform(post("/api/v1/admin/mail-deliveries/" + delivery(failed) + "/resend")
                        .header("Authorization", token(user(UserRole.SYS_ADMIN))))
                .andExpect(status().isConflict());
        UUID source = jdbc.queryForObject("select public_id from notifications where id = ?", UUID.class, failed);
        mvc.perform(post("/api/v1/admin/notifications/" + source + "/resend")
                        .header("Authorization", token(user(UserRole.SYS_ADMIN))))
                .andExpect(status().isConflict());
    }

    @Test
    void aLegacyFailureDoesNotProveSmtpRejectionEvenWithAKnownAddress() throws Exception {
        long failed = notification(user(UserRole.USER), "FAILED", "historic@pickle.invalid", false, 3);
        journal.importLegacyBatch(1000);
        mvc.perform(get("/api/v1/admin/mail-deliveries/" + delivery(failed))
                        .header("Authorization", token(user(UserRole.SYS_ADMIN))))
                .andExpect(jsonPath("$.delivery.canResend").value(false))
                .andExpect(jsonPath("$.delivery.cannotResendReason").value("LEGACY_FAILURE_UNCONFIRMED"));
    }

    @Test
    void anUnownedAccountTaskKeepsItsStoredQueueFilterAndSeparateObservation() throws Exception {
        String email = "interrupted." + UUID.randomUUID() + "@pickle.invalid";
        journal.enqueueAccount(email, "account.password_reset", null, Instant.now().plusSeconds(300));
        mvc.perform(get("/api/v1/admin/mail-deliveries").param("sourceKind", "ACCOUNT")
                        .param("queueState", "ACCOUNT_WAIT").param("email", email)
                        .header("Authorization", token(user(UserRole.SYS_VIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].queueState").value("ACCOUNT_WAIT"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].processingUnconfirmed").value(true));
    }

    private long notification(User user, String state, String email, boolean bundle, int attempts) {
        return jdbc.queryForObject("""
                insert into notifications (user_id, event, title, body, status, recipient_email, bundle, attempts)
                values (?, 'request.submitted', '합성 발송 확인', '보존 기간 후 정리할 본문', ?::notification_status, ?, ?, ?)
                returning id
                """, Long.class, user.getId(), state, email, bundle, attempts);
    }

    private UUID delivery(long notificationId) {
        return jdbc.queryForObject("select public_id from mail_deliveries where notification_id = ?", UUID.class, notificationId);
    }

    private User user(UserRole role) {
        var user = new User("md." + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "합성 " + role);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private String token(User user) { return "Bearer " + jwt.createAccessToken(user); }
}
