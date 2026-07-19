package kr.ac.pusan.pickle.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import kr.ac.pusan.pickle.config.TerminalProperties;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.Test;

/**
 * Mirror leak-prevention (coordinator review MAJOR): a bridge that dies between
 * redeem and session-start (PENDING leak) or is hard-killed mid-session (STARTED
 * leak, session-end never arrives) must not pin a cap slot forever. {@code prune}
 * evicts PENDING past {@code pendingGrace} and STARTED past {@code staleAfter},
 * and the revalidation {@code touch} heartbeat keeps a live session alive. Pure
 * unit with a hand-advanced clock.
 */
class TerminalSessionRegistryTest {

    private static final Instant T0 = Instant.parse("2026-07-20T00:00:00Z");
    private static final Duration PENDING_GRACE = Duration.ofSeconds(120);
    private static final Duration STALE_AFTER = Duration.ofSeconds(330);

    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static TerminalProperties props() {
        return new TerminalProperties(null, null, null, null, null, null, null, null, null,
                PENDING_GRACE, STALE_AFTER, false);
    }

    @Test
    void pendingLeakIsPrunedAfterGraceAndFreesTheCap() {
        TestClock clock = new TestClock(T0);
        TerminalSessionRegistry registry = new TerminalSessionRegistry(clock, props());
        registry.registerPending("pending-1", 42L, UserRole.USER, 55L, 7L);
        assertThat(registry.countUser(42L)).isEqualTo(1);

        clock.advance(PENDING_GRACE.plusSeconds(1));
        registry.prune();

        assertThat(registry.get("pending-1")).isEmpty();
        assertThat(registry.countUser(42L)).isZero();
    }

    @Test
    void pendingSurvivesWithinGrace() {
        TestClock clock = new TestClock(T0);
        TerminalSessionRegistry registry = new TerminalSessionRegistry(clock, props());
        registry.registerPending("pending-2", 1L, UserRole.USER, 2L, 3L);

        clock.advance(PENDING_GRACE.minusSeconds(1));
        registry.prune();

        assertThat(registry.get("pending-2")).isPresent();
    }

    @Test
    void startedLeakIsPrunedAfterStaleAndLeavesListAndCap() {
        TestClock clock = new TestClock(T0);
        TerminalSessionRegistry registry = new TerminalSessionRegistry(clock, props());
        registry.registerPending("started-1", 9L, UserRole.USER, 100L, 5L);
        registry.markStarted("started-1", "203.0.113.5");
        assertThat(registry.started()).hasSize(1);

        clock.advance(STALE_AFTER.plusSeconds(1));
        registry.prune();

        assertThat(registry.started()).isEmpty();
        assertThat(registry.countVm(100L)).isZero();
    }

    @Test
    void revalidationHeartbeatKeepsStartedSessionAlive() {
        TestClock clock = new TestClock(T0);
        TerminalSessionRegistry registry = new TerminalSessionRegistry(clock, props());
        registry.registerPending("started-2", 9L, UserRole.USER, 101L, 5L);
        registry.markStarted("started-2", "203.0.113.6");

        // advance most of the stale window, then the bridge polls (touch)...
        clock.advance(STALE_AFTER.minusSeconds(10));
        registry.touch("started-2");
        // ...and advance again: total elapsed > staleAfter, but last heartbeat is recent.
        clock.advance(STALE_AFTER.minusSeconds(10));
        registry.prune();

        assertThat(registry.get("started-2")).isPresent();
        assertThat(registry.started()).hasSize(1);
    }
}
