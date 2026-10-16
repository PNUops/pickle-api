package kr.ac.pusan.pickle.common.tx;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
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
    /** The isolating caller's context (a bulk batch, say), or absent when not isolating. */
    private static final ThreadLocal<String> ISOLATING = new ThreadLocal<>();

    @FunctionalInterface
    public interface Completion { void observed(boolean success, @Nullable String reference); }
    @FunctionalInterface
    public interface Observer { Completion registered(String label); }
    private static final ThreadLocal<Observer> OBSERVER = new ThreadLocal<>();

    public static <T> T observing(Observer observer, Supplier<T> body) {
        Observer previous = OBSERVER.get();
        OBSERVER.set(observer);
        try { return body.get(); } finally {
            if (previous == null) { OBSERVER.remove(); } else { OBSERVER.set(previous); }
        }
    }

    /** A non-null returned scheduler reference proves queue acceptance, never execution completion. */
    public static void queue(String label, Supplier<@Nullable String> action) {
        register(label, action);
    }

    private AfterCommit() {
    }

    /**
     * Runs {@code action} after the current transaction commits.
     * {@code label} names the work for the log line an isolated failure
     * leaves, such as {@code "audit vm.admin_start <publicId>"}.
     */
    public static void run(String label, Runnable action) {
        register(label, () -> { action.run(); return null; });
    }

    private static void register(String label, Supplier<@Nullable String> action) {
        String context = ISOLATING.get();
        Observer observer = OBSERVER.get();
        Completion completion = observer == null ? null : observer.registered(label);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                boolean success = false;
                String reference = null;
                try {
                    reference = action.get();
                    success = true;
                } catch (RuntimeException e) {
                    if (context == null) { throw e; }
                    log.error("after-commit {} failed ({}); the remaining ones still run", label, context, e);
                } finally {
                    if (completion != null) {
                        try { completion.observed(success, reference); }
                        catch (RuntimeException e) {
                            log.error("after-commit observation {} failed; committed work is retained", label, e);
                        }
                    }
                }
            }
        });
    }

    /**
     * Runs {@code body} with every after-commit callback it registers
     * isolated from the others. {@code context} goes into each isolated
     * failure's log line, such as {@code "batch <batchId>"}. The previous
     * mode is restored however {@code body} ends.
     */
    public static <T> T isolating(String context, Supplier<T> body) {
        @Nullable String previous = ISOLATING.get();
        ISOLATING.set(context);
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
