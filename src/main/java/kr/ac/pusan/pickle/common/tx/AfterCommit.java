package kr.ac.pusan.pickle.common.tx;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Work that runs once the surrounding transaction has committed: a job
 * enqueue, a push, an audit row.
 *
 * <p>Spring runs a transaction's after-commit callbacks in order and stops at
 * the first one that throws, so every callback registered after it is lost.
 * For one change that is the right failure: the caller is told, and there is
 * nothing else in the transaction to lose. A transaction carrying many
 * independent targets is different, because one target's failed enqueue would
 * silently take the next targets' enqueues and audit rows with it. Such a
 * caller wraps its work in {@link #isolating}, and every callback registered
 * inside is caught and logged on its own, so the others still run.
 *
 * <p>The choice is made when the callback is registered, not when it runs,
 * so a single path registering outside {@link #isolating} behaves exactly as
 * a bare synchronization does.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);
    private static final ThreadLocal<Boolean> ISOLATING = new ThreadLocal<>();

    private AfterCommit() {
    }

    /** Runs {@code action} after the current transaction commits. */
    public static void run(Runnable action) {
        boolean isolated = Boolean.TRUE.equals(ISOLATING.get());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (!isolated) {
                    action.run();
                    return;
                }
                try {
                    action.run();
                } catch (RuntimeException e) {
                    // The transaction has committed; all that is left is to
                    // say so and let the remaining callbacks run.
                    log.error("after-commit action failed; the remaining ones still run", e);
                }
            }
        });
    }

    /**
     * Runs {@code body} with every after-commit callback it registers
     * isolated from the others.
     */
    public static <T> T isolating(Supplier<T> body) {
        Boolean previous = ISOLATING.get();
        ISOLATING.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                ISOLATING.remove();
            } else {
                ISOLATING.set(previous);
            }
        }
    }
}
