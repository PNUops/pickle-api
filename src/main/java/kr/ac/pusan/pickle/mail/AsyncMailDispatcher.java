package kr.ac.pusan.pickle.mail;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Hands account mail to a background pool once the surrounding transaction has
 * committed.
 *
 * <p>Signup, verification resend and password reset answer uniformly whether or
 * not the address is on file, so the response <em>time</em> must not depend on
 * whether a mail went out either: a real SMTP send costs hundreds of
 * milliseconds, and a request that waits for one tells the caller that a mail
 * was sent, and therefore that the address exists. Dispatching here keeps every
 * such response equally fast, which also means the mail suppression windows
 * (e.g. one already-registered notice per hour) stay invisible from outside.</p>
 *
 * <p>Delivery metadata joins the caller's transaction, and SMTP leaves only after
 * that transaction commits. Delivery failures never change the caller's response
 * and are recorded without an automatic retry. Bodies and tokens stay in this
 * process's bounded memory queue.</p>
 */
@Component
public class AsyncMailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsyncMailDispatcher.class);

    private static final int THREADS = 2;
    /** Sends queued beyond this are dropped rather than allowed to grow unbounded. */
    private static final int QUEUE_CAPACITY = 1000;
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);
    private static final long IDLE_POLL_MILLIS = 5;

    private final MailSender mailSender;
    private final MailDeliveryJournal journal;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<UUID, AccountSend> owned = new ConcurrentHashMap<>();
    /** Dispatched but not yet finished sends; {@link #awaitIdle} waits on this. */
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean stopping;

    @Autowired
    public AsyncMailDispatcher(MailSender mailSender, MailDeliveryJournal journal) {
        this(mailSender, journal, THREADS, QUEUE_CAPACITY);
    }

    AsyncMailDispatcher(MailSender mailSender, MailDeliveryJournal journal, int threads, int queueCapacity) {
        this.mailSender = mailSender;
        this.journal = journal;
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "account-mail");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Queues {@code message} for background delivery: after commit when a
     * transaction is running, immediately otherwise. Metadata registration uses
     * the caller's transaction; SMTP and subsequent evidence failures remain in
     * the background process.
     */
    @Transactional
    public void dispatch(MailMessage message) {
        dispatch(message, "account.mail", null, null);
    }

    /** Registers metadata in the caller's transaction; tokens and bodies are never journaled. */
    @Transactional
    public void dispatch(MailMessage message, String event, @Nullable UUID userPublicId,
            @Nullable Instant expiresAt) {
        UUID deliveryId = journal.enqueueAccount(message.to(), event, userPublicId, expiresAt);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(message, deliveryId, expiresAt);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(message, deliveryId, expiresAt);
            }
        });
    }

    private void submit(MailMessage message, UUID deliveryId, @Nullable Instant expiresAt) {
        var task = new AccountSend(message, deliveryId, expiresAt);
        owned.put(deliveryId, task);
        inFlight.incrementAndGet();
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            task.cancelQueued(executor.isShutdown() ? "QUEUE_STOPPED" : "QUEUE_FULL");
        }
    }

    /** This process can account for these in-memory messages; another process cannot resume them. */
    public boolean ownsAccount(UUID deliveryId) {
        return owned.containsKey(deliveryId);
    }

    private final class AccountSend implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;
        private final MailMessage message;
        private final UUID deliveryId;
        private final UUID owner = UUID.randomUUID();
        private final @Nullable Instant expiresAt;
        private final AtomicInteger phase = new AtomicInteger(QUEUED);

        AccountSend(MailMessage message, UUID deliveryId, @Nullable Instant expiresAt) {
            this.message = message;
            this.deliveryId = deliveryId;
            this.expiresAt = expiresAt;
        }

        @Override
        public void run() {
            if (!phase.compareAndSet(QUEUED, RUNNING)) return;
            try {
                if (stopping) {
                    journal.skipAccount(deliveryId, "QUEUE_STOPPED");
                    return;
                }
                if (expiresAt != null && !Instant.now().isBefore(expiresAt)) {
                    journal.skipAccount(deliveryId, "EXPIRED_MESSAGE");
                    return;
                }
                if (!journal.beginAccountAttempt(deliveryId, owner)) return;
                try {
                    mailSender.send(message);
                } catch (RuntimeException failure) {
                    var classified = MailDeliveryFailure.classify(failure);
                    finish(classified.definiteFailure() ? "FAILED" : "UNKNOWN", classified.code());
                    return;
                }
                // A successful SMTP return and a failed DB write are separate outcomes.
                boolean interrupted = Thread.interrupted();
                try {
                    journal.finishAccountAttempt(deliveryId, owner, "SENT", null);
                } catch (RuntimeException persistenceFailure) {
                    finish("UNKNOWN", "RESULT_PERSISTENCE_FAILED");
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            } catch (RuntimeException evidenceFailure) {
                // Never send when its claim could not be recorded, and never log message material.
                log.warn("account mail evidence unavailable for {}", deliveryId);
            } finally {
                phase.set(FINISHED);
                complete();
            }
        }

        private void finish(String state, @Nullable String code) {
            boolean interrupted = Thread.interrupted();
            try {
                journal.finishAccountAttempt(deliveryId, owner, state, code);
            } catch (RuntimeException persistenceFailure) {
                log.warn("account mail outcome unconfirmed for {} ({})", deliveryId, code);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        void cancelQueued(String code) {
            if (!phase.compareAndSet(QUEUED, FINISHED)) return;
            try {
                journal.skipAccount(deliveryId, code);
            } catch (RuntimeException evidenceFailure) {
                log.warn("account mail queue outcome unconfirmed for {} ({})", deliveryId, code);
            } finally {
                complete();
            }
        }

        void interruptRunning() {
            if (phase.get() == RUNNING) finish("UNKNOWN", "PROCESS_STOPPED");
        }

        private void complete() {
            if (owned.remove(deliveryId, this)) inFlight.decrementAndGet();
        }
    }

    /**
     * Blocks until every dispatched send has finished, at most {@code timeout};
     * returns false if sends were still pending when it gave up. Used on shutdown
     * so a graceful stop does not silently drop queued mail, and by tests as a
     * barrier before asserting on what was sent.
     */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight.get() > 0) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            try {
                Thread.sleep(IDLE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @PreDestroy
    void shutdown() {
        shutdown(SHUTDOWN_GRACE);
    }

    void shutdown(Duration grace) {
        executor.shutdown();
        if (!awaitIdle(grace)) {
            log.warn("shutting down with {} account mail(s) still pending", inFlight.get());
        }
        stopping = true;
        // Stop queued messages before interruption; original running owners may report a late result.
        owned.values().forEach(task -> task.cancelQueued("QUEUE_STOPPED"));
        owned.values().forEach(AccountSend::interruptRunning);
        executor.shutdownNow();
    }
}
