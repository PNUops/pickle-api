package kr.ac.pusan.pickle.common.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The two modes of an after-commit callback, driven the way Spring drives
 * them: in registration order, stopping at the first that throws.
 */
class AfterCommitTest {

    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void startSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void endSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void outsideIsolationAFailureStopsTheCallbacksAfterIt() {
        AfterCommit.run("first", () -> {
            throw new IllegalStateException("boom");
        });
        AfterCommit.run("second", () -> ran.add("second"));

        assertThatThrownBy(this::commit).isInstanceOf(IllegalStateException.class);
        assertThat(ran).isEmpty();
    }

    @Test
    void insideIsolationEachFailureIsItsOwn() {
        AfterCommit.isolating("batch test", () -> {
            AfterCommit.run("first", () -> {
                throw new IllegalStateException("boom");
            });
            AfterCommit.run("second", () -> ran.add("second"));
            return null;
        });

        commit();
        assertThat(ran).containsExactly("second");
    }

    @Test
    void theModeIsRestoredWhenTheIsolatedBodyThrows() {
        assertThatThrownBy(() -> AfterCommit.isolating("batch test", () -> {
            throw new IllegalArgumentException("body failed");
        })).isInstanceOf(IllegalArgumentException.class);

        AfterCommit.run("after", () -> {
            throw new IllegalStateException("boom");
        });
        assertThatThrownBy(this::commit).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOuterIsolationSurvivesAnInnerOne() {
        AfterCommit.isolating("outer", () -> {
            AfterCommit.isolating("inner", () -> null);
            AfterCommit.run("outer callback", () -> {
                throw new IllegalStateException("boom");
            });
            return null;
        });

        commit();
    }

    private void commit() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }
}
