package kr.ac.pusan.pickle.mail;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
 * <p>Three consequences are deliberate: the send can neither roll back nor delay
 * the caller's transaction, a delivery failure never changes the response (it is
 * logged and dropped), and a mail leaves only after the row it refers to (a
 * verification or reset token) is committed and therefore usable.</p>
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
    private final ThreadPoolExecutor executor;
    /** Dispatched but not yet finished sends; {@link #awaitIdle} waits on this. */
    private final AtomicInteger inFlight = new AtomicInteger();

    public AsyncMailDispatcher(MailSender mailSender) {
        this.mailSender = mailSender;
        this.executor = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "account-mail");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Queues {@code message} for background delivery: after commit when a
     * transaction is running, immediately otherwise. Never throws.
     */
    public void dispatch(MailMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(message);
            }
        });
    }

    private void submit(MailMessage message) {
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    mailSender.send(message);
                } catch (RuntimeException e) {
                    // Best effort by design: the caller already answered, and the
                    // person can retry the action (resend / reset) themselves.
                    log.warn("account mail send failed (subject {}): {}", message.subject(), e.toString());
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            log.warn("account mail dropped, dispatch queue full (subject {})", message.subject());
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
        if (!awaitIdle(SHUTDOWN_GRACE)) {
            log.warn("shutting down with {} account mail(s) still pending", inFlight.get());
        }
        executor.shutdownNow();
    }
}
