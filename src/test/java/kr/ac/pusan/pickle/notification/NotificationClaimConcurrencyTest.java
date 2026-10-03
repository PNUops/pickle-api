package kr.ac.pusan.pickle.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.mail.MailSender;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class NotificationClaimConcurrencyTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private kr.ac.pusan.pickle.mail.MailDeliveryJournal journal;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void anOverlappingDispatcherCannotSendAClaimedMessageAgain() throws Exception {
        jdbc.update("update notifications set status = 'SENT' where status = 'PENDING'");
        var user = new User("claim." + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "합성 수신자");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user = users.saveAndFlush(user);
        jdbc.update("""
                insert into notifications (user_id, event, title, body, link_path, bundle)
                values (?, 'request.submitted', '합성 신청 확인', '중복 방지 대조', '/notifications', false)
                """, user.getId());

        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        MailSender sender = message -> {
            if (calls.incrementAndGet() == 1) {
                sending.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("send barrier timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
        };
        var dispatcher = new NotificationDispatchJob(jdbc, sender, "https://pickle.invalid", Duration.ofMinutes(60), journal, transactions);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(dispatcher::dispatch);
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            var second = threads.submit(dispatcher::dispatch);
            second.get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(calls.get()).as("SMTP invocations for a single queued notification").isEqualTo(1);
    }

    @Test
    void acknowledgedHandoffIsNotRetriedWhenResultPersistenceFails() {
        jdbc.update("update notifications set status = 'SENT' where status = 'PENDING'");
        var user = new User("outcome." + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "합성 수신자");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user = users.saveAndFlush(user);
        long id = jdbc.queryForObject("""
                insert into notifications (user_id, event, title, body, link_path, bundle)
                values (?, 'request.submitted', '합성 신청 확인', '결과 저장 실패 대조', '/notifications', false)
                returning id
                """, Long.class, user.getId());
        jdbc.execute("""
                create function fail_synthetic_mail_outcome() returns trigger language plpgsql as $$
                begin
                    if new.id = %d and new.status = 'SENT' then
                        raise exception 'synthetic result persistence failure';
                    end if;
                    return new;
                end $$
                """.formatted(id));
        jdbc.execute("""
                create trigger synthetic_mail_outcome_failure before update on notifications
                for each row execute function fail_synthetic_mail_outcome()
                """);
        var calls = new AtomicInteger();
        MailSender sender = message -> calls.incrementAndGet();
        var dispatcher = new NotificationDispatchJob(jdbc, sender, "https://pickle.invalid", Duration.ofMinutes(60), journal, transactions);
        try {
            dispatchAllowingResultFailure(dispatcher);
            // Advance the retry deadline without a wall-clock sleep.
            jdbc.update("update notifications set next_attempt_at = now() where id = ?", id);
            dispatchAllowingResultFailure(dispatcher);
            assertThat(calls.get()).as("SMTP handoffs after a result-storage failure").isEqualTo(1);
        } finally {
            jdbc.execute("drop trigger synthetic_mail_outcome_failure on notifications");
            jdbc.execute("drop function fail_synthetic_mail_outcome()");
        }
    }

    private static void dispatchAllowingResultFailure(NotificationDispatchJob dispatcher) {
        try {
            dispatcher.dispatch();
        } catch (DataAccessException expectedResultFailure) {
            // A DB fault after SMTP handoff can propagate without scheduling another send.
        }
    }

    @Test
    void aLegacyAttemptRecordsTheExactAddressPassedToSmtp() throws Exception {
        User recipient = recipient();
        long id = pending(recipient, false);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sentTo = new java.util.concurrent.atomic.AtomicReference<String>();
        var dispatcher = new NotificationDispatchJob(jdbc, message -> sentTo.set(message.to()),
                "https://pickle.invalid", Duration.ofMinutes(60), journal, transactions);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var change = threads.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(tx -> {
                        lockRecipient(recipient.getId());
                        held.countDown();
                        await(release);
                        jdbc.update("update users set email = ? where id = ?",
                                "changed." + UUID.randomUUID() + "@pusan.ac.kr", recipient.getId());
                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var sending = threads.submit(dispatcher::dispatch);
            waitForRecipientLock();
            release.countDown();
            change.get(5, TimeUnit.SECONDS);
            sending.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(sentTo.get()).isEqualTo(recipient.getEmail());
        assertThat(jdbc.queryForObject("select recipient_email from mail_deliveries where notification_id = ?",
                String.class, id)).isEqualTo(sentTo.get());
    }

    @Test
    void bundleClaimRechecksTheWindowAfterWaitingForAnotherDispatcher() throws Exception {
        User recipient = recipient();
        long id = pending(recipient, true);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var dispatcher = new NotificationDispatchJob(jdbc, message -> calls.incrementAndGet(),
                "https://pickle.invalid", Duration.ofMinutes(60), journal, transactions);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var other = threads.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(tx -> {
                        lockRecipient(recipient.getId());
                        held.countDown();
                        await(release);
                        jdbc.update("""
                                insert into notifications (user_id, event, title, body, status, bundle, recipient_email, sent_at)
                                values (?, 'request.submitted', '다른 묶음의 인계', '합성', 'SENT', true, ?, now())
                                """, recipient.getId(), recipient.getEmail());
                    }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var sending = threads.submit(dispatcher::dispatch);
            waitForRecipientLock();
            release.countDown();
            other.get(5, TimeUnit.SECONDS);
            sending.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(calls.get()).isZero();
        assertThat(jdbc.queryForObject("select status::text from notifications where id = ?", String.class, id)).isEqualTo("PENDING");
    }

    @Test
    void anExpiredAttemptIsNotReclaimedAndItsOriginalLateResultCanFinish() throws Exception {
        User recipient = recipient();
        long id = pending(recipient, false);
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var dispatcher = new NotificationDispatchJob(jdbc, message -> {
            calls.incrementAndGet(); sending.countDown(); await(release);
        }, "https://pickle.invalid", Duration.ofMinutes(60), journal, transactions);
        try (var threads = Executors.newSingleThreadExecutor()) {
            var first = threads.submit(dispatcher::dispatch);
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update("update notifications set delivery_claimed_at = now() - interval '11 minutes' where id = ?", id);
            dispatcher.dispatch();
            assertThat(jdbc.queryForObject("select status::text from notifications where id = ?", String.class, id)).isEqualTo("UNKNOWN");
            assertThat(calls.get()).isEqualTo(1);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(jdbc.queryForObject("select state from mail_deliveries where notification_id = ?", String.class, id)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select count(*) from mail_delivery_attempts a join mail_deliveries d on d.id = a.delivery_id"
                + " where d.notification_id = ? and outcome = 'SENT'", Integer.class, id)).isEqualTo(1);
    }

    private User recipient() {
        jdbc.update("update notifications set status = 'SENT' where status = 'PENDING'");
        var recipient = new User("concurrent." + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "합성 수신자");
        recipient.setStatus(UserStatus.ACTIVE);
        recipient.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(recipient);
    }

    private long pending(User recipient, boolean bundle) {
        return jdbc.queryForObject("""
                insert into notifications (user_id, event, title, body, status, bundle)
                values (?, 'request.submitted', '동시성 확인', '합성', 'PENDING', ?) returning id
                """, Long.class, recipient.getId(), bundle);
    }

    private void lockRecipient(long id) {
        jdbc.queryForObject("select pg_advisory_xact_lock(1129074512, ?::integer)::text", String.class, id % Integer.MAX_VALUE);
    }

    private void waitForRecipientLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("select count(*) from pg_locks where locktype = 'advisory' and classid = 1129074512 and not granted", Integer.class) > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("dispatcher did not wait for the recipient claim lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
