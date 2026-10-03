package kr.ac.pusan.pickle.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class MailDeliveryJournalTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private MailDeliveryJournal journal;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void accountMetadataRollsBackWithTheCallerAndNeverSends() {
        var sends = new AtomicInteger();
        var dispatcher = new AsyncMailDispatcher(message -> sends.incrementAndGet(), journal, 1, 1);
        String address = address();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                dispatcher.dispatch(message(address), "account.signup_verification", UUID.randomUUID(),
                        Instant.now().plusSeconds(300));
                tx.setRollbackOnly();
            });
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(1))).isTrue();
            assertThat(sends).hasValue(0);
            assertThat(jdbc.queryForObject("select count(*) from mail_deliveries where recipient_email = ?",
                    Integer.class, address)).isZero();
        } finally { dispatcher.shutdown(Duration.ZERO); }
    }

    @Test
    void accountHistoryKeepsSafeCatalogMetadataWithoutBodiesOrAuthenticationMaterial() {
        var sends = new AtomicInteger();
        var dispatcher = new AsyncMailDispatcher(message -> sends.incrementAndGet(), journal, 1, 1);
        String address = address();
        try {
            dispatcher.dispatch(message(address), "account.password_reset", UUID.randomUUID(),
                    Instant.now().plusSeconds(300));
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
            Map<String, Object> row = account(address);
            assertThat(row.get("state")).isEqualTo("SENT");
            assertThat(row.get("title")).isEqualTo("비밀번호 재설정");
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat(row.get("link_path")).isNull();
            assertThat(row.toString()).doesNotContain("secret-token", "secret-body", "private-subject");
            assertThat(attempts((UUID) row.get("public_id"))).containsExactly("SENT");
            assertThat(sends).hasValue(1);
            assertThat(dispatcher.ownsAccount((UUID) row.get("public_id"))).isFalse();
        } finally { dispatcher.shutdown(Duration.ZERO); }
    }

    @Test
    void aFullMemoryQueueRecordsAnUnsentMessageWithoutAnAttempt() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var dispatcher = new AsyncMailDispatcher(message -> {
            started.countDown();
            await(release);
        }, journal, 1, 1);
        String first = address();
        String queued = address();
        String rejected = address();
        try {
            dispatcher.dispatch(message(first));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.dispatch(message(queued));
            dispatcher.dispatch(message(rejected));
            Map<String, Object> row = account(rejected);
            assertThat(row.get("state")).isEqualTo("SKIPPED");
            assertThat(row.get("failure_code")).isEqualTo("QUEUE_FULL");
            assertThat(row.get("attempts")).isEqualTo(0);
            assertThat(attempts((UUID) row.get("public_id"))).isEmpty();
            assertThat(dispatcher.ownsAccount((UUID) account(queued).get("public_id"))).isTrue();
            release.countDown();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        } finally {
            release.countDown();
            dispatcher.shutdown(Duration.ZERO);
        }
    }

    @Test
    void aResultStorageFailureRecordsUnknownAndDoesNotSendAgain() {
        String address = address();
        String function = "fail_account_outcome_" + UUID.randomUUID().toString().replace("-", "");
        String trigger = function + "_trigger";
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $$ begin "
                + "if new.recipient_email = '" + address + "' and new.state = 'SENT' then "
                + "raise exception 'synthetic account outcome failure'; end if; return new; end $$");
        jdbc.execute("create trigger " + trigger + " before update on mail_deliveries "
                + "for each row execute function " + function + "()");
        var sends = new AtomicInteger();
        var dispatcher = new AsyncMailDispatcher(message -> sends.incrementAndGet(), journal, 1, 1);
        try {
            dispatcher.dispatch(message(address));
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
            Map<String, Object> row = jdbc.queryForMap("select * from mail_deliveries where recipient_email = ? "
                    + "order by id desc limit 1", address);
            assertThat(row.get("state")).isEqualTo("UNKNOWN");
            assertThat(row.get("failure_code")).isEqualTo("RESULT_PERSISTENCE_FAILED");
            assertThat(attempts((UUID) row.get("public_id"))).containsExactly("UNKNOWN");
            assertThat(sends).hasValue(1);
            assertThat(journal.beginAccountAttempt((UUID) row.get("public_id"), UUID.randomUUID())).isFalse();
            assertThat(sends).hasValue(1);
        } finally {
            dispatcher.shutdown(Duration.ZERO);
            jdbc.execute("drop trigger " + trigger + " on mail_deliveries");
            jdbc.execute("drop function " + function + "()");
        }
    }

    @Test
    void shutdownSkipsQueuedMessagesAndKeepsRunningResultsUnconfirmedUntilTheirOwnerReturns() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var dispatcher = new AsyncMailDispatcher(message -> {
            started.countDown();
            boolean interrupted = false;
            while (release.getCount() > 0) {
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }, journal, 1, 1);
        String running = address();
        String queued = address();
        try {
            dispatcher.dispatch(message(running));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.dispatch(message(queued));
            dispatcher.shutdown(Duration.ZERO);
            assertThat(account(queued).get("state")).isEqualTo("SKIPPED");
            assertThat(account(queued).get("failure_code")).isEqualTo("QUEUE_STOPPED");
            assertThat(account(queued).get("attempts")).isEqualTo(0);
            assertThat(account(running).get("state")).isEqualTo("UNKNOWN");
            release.countDown();
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(account(running).get("state")).isEqualTo("SENT");
        } finally {
            release.countDown();
            dispatcher.shutdown(Duration.ZERO);
        }
    }

    @Test
    void anExpiredQueuedLinkIsSkippedWithoutCallingTheSender() {
        var sends = new AtomicInteger();
        var dispatcher = new AsyncMailDispatcher(message -> sends.incrementAndGet(), journal, 1, 1);
        String address = address();
        try {
            dispatcher.dispatch(message(address), "account.password_reset", null, Instant.now().minusSeconds(1));
            assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(account(address).get("state")).isEqualTo("SKIPPED");
            assertThat(account(address).get("failure_code")).isEqualTo("EXPIRED_MESSAGE");
            assertThat(sends).hasValue(0);
        } finally { dispatcher.shutdown(Duration.ZERO); }
    }

    @Test
    void legacyImportPreservesUnknownAddressAndSurvivesInboxDeletion() {
        long id = notification("SENT");
        UUID sourceId = jdbc.queryForObject("select public_id from notifications where id = ?", UUID.class, id);
        for (int batch = 0; batch < 100 && !hasDelivery(sourceId); batch++) journal.importLegacyBatch(1000);
        Map<String, Object> row = jdbc.queryForMap("select * from mail_deliveries where notification_public_id = ?", sourceId);
        assertThat(row.get("recipient_email")).isNull();
        assertThat(row.get("failure_code")).isEqualTo("LEGACY_STATUS_IMPORT");
        assertThat(attempts((UUID) row.get("public_id"))).isEmpty();
        jdbc.update("delete from notifications where id = ?", id);
        Map<String, Object> preserved = jdbc.queryForMap("select * from mail_deliveries where notification_public_id = ?", sourceId);
        assertThat(preserved.get("notification_id")).isNull();
        assertThat(preserved.get("state")).isEqualTo("SENT");
    }

    @Test
    void explicitImportsPreserveMissingAddressesAndAnnouncementInstitutionLinks() {
        long id = notification("PENDING");
        long userId = jdbc.queryForObject("select user_id from notifications where id = ?", Long.class, id);
        UUID orgId = jdbc.queryForObject("insert into orgs(name) values (?) returning public_id", UUID.class,
                "합성 발송 기관 " + UUID.randomUUID());
        UUID announcementId = jdbc.queryForObject("""
                insert into announcements(author_id, scope, org_id, title, body)
                values (?, 'ORG', (select id from orgs where public_id = ?), '합성 발송', 'secret-body')
                returning public_id
                """, UUID.class, userId, orgId);
        jdbc.update("update notifications set announcement_id = (select id from announcements where public_id = ?) where id = ?",
                announcementId, id);
        journal.importNotifications(List.of(id));
        var row = jdbc.queryForMap("select recipient_email, org_id, announcement_id from mail_deliveries where notification_id = ?", id);
        assertThat(row.get("recipient_email")).isNull();
        assertThat(row.get("org_id")).isEqualTo(orgId);
        assertThat(row.get("announcement_id")).isEqualTo(announcementId);
    }

    @Test
    void accountSenderFailureIsClassifiedWithoutAnAutomaticSecondAttempt() {
        for (boolean definite : new boolean[] {true, false}) {
            String address = address();
            var sends = new AtomicInteger();
            var dispatcher = new AsyncMailDispatcher(message -> {
                sends.incrementAndGet();
                throw definite ? MailDeliveryFailure.rejected() : new IllegalStateException("secret-token secret-body");
            }, journal, 1, 1);
            try {
                dispatcher.dispatch(message(address));
                assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
                var row = account(address);
                assertThat(row.get("state")).isEqualTo(definite ? "FAILED" : "UNKNOWN");
                assertThat(row.get("failure_code")).isEqualTo(definite ? "SMTP_REJECTED" : "MAIL_DELIVERY_UNKNOWN");
                assertThat(row.toString()).doesNotContain("secret-token", "secret-body");
                assertThat(journal.beginAccountAttempt((UUID) row.get("public_id"), UUID.randomUUID())).isFalse();
                assertThat(sends).hasValue(1);
            } finally { dispatcher.shutdown(Duration.ZERO); }
        }
    }

    @Test
    void notificationHistoryUsesClaimOwnershipAndSharesBusinessRollback() {
        long id = notification("PENDING");
        UUID owner = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            journal.importNotifications(List.of(id));
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from mail_deliveries where notification_id = ?",
                Integer.class, id)).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("update notifications set status = 'SENDING', attempts = attempts + 1, "
                    + "delivery_claim_id = ?, delivery_claimed_at = now() where id = ?", owner, id);
            journal.beginNotificationAttempt(List.of(id), owner);
            journal.finishNotificationAttempt(List.of(id), UUID.randomUUID(), "SENT", null, null);
        });
        assertThat(jdbc.queryForObject("select state from mail_deliveries where notification_id = ?", String.class, id))
                .isEqualTo("SENDING");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            journal.finishNotificationAttempt(List.of(id), owner, "PENDING", "SMTP_REJECTED", Instant.now().plusSeconds(60));
        });
        UUID deliveryId = jdbc.queryForObject("select public_id from mail_deliveries where notification_id = ?", UUID.class, id);
        assertThat(attempts(deliveryId)).containsExactly("FAILED");
        assertThat(jdbc.queryForObject("select state from mail_deliveries where notification_id = ?", String.class, id))
                .isEqualTo("PENDING");
    }

    private boolean hasDelivery(UUID notificationId) {
        return jdbc.queryForObject("select count(*) from mail_deliveries where notification_public_id = ?",
                Integer.class, notificationId) > 0;
    }

    private long notification(String state) {
        User user = new User(address(), "{test-no-login}", "합성 journal 수신자");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user = users.saveAndFlush(user);
        return jdbc.queryForObject("insert into notifications(user_id,event,title,body,status,attempts) "
                + "values (?, 'request.submitted', '합성 확인', 'secret-body', ?::notification_status, ?) returning id",
                Long.class, user.getId(), state, "SENT".equals(state) ? 2 : 0);
    }

    private Map<String, Object> account(String address) {
        return jdbc.queryForMap("select * from mail_deliveries where source_kind = 'ACCOUNT' and recipient_email = ?", address);
    }

    private List<String> attempts(UUID deliveryId) {
        return jdbc.queryForList("select a.outcome from mail_delivery_attempts a join mail_deliveries d "
                + "on d.id = a.delivery_id where d.public_id = ? order by a.attempt_no", String.class, deliveryId);
    }

    private static MailMessage message(String address) {
        return new MailMessage(address, "private-subject", "secret-body https://pickle.invalid/verify?token=secret-token", null);
    }

    private static String address() { return "journal." + UUID.randomUUID() + "@pusan.ac.kr"; }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic send barrier timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("synthetic send interrupted");
        }
    }
}
