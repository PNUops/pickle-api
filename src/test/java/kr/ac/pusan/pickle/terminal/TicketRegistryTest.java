package kr.ac.pusan.pickle.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.config.TerminalProperties;
import kr.ac.pusan.pickle.terminal.TicketRegistry.Minted;
import kr.ac.pusan.pickle.terminal.TicketRegistry.Ticket;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.Test;

/**
 * Ticket store invariants: user/VM binding, TTL
 * expiry (deterministic via an injected clock), atomic single-use under a
 * concurrent-redeem race, and cap counting. Pure unit — no Spring context.
 */
class TicketRegistryTest {

    private static final Instant T0 = Instant.parse("2026-07-20T00:00:00Z");

    /** A hand-advanced clock so TTL expiry is exact and race-free. */
    private static final class TestClock extends Clock {
        private volatile Instant now;

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

    private static TerminalProperties propsWithTtl(Duration ttl) {
        return new TerminalProperties(null, null, null, null, ttl, null, null, null, null, null,
                null, false);
    }

    @Test
    void ticketBindsSessionUserVmAndRedeemsExactlyOnce() {
        TestClock clock = new TestClock(T0);
        TicketRegistry registry = new TicketRegistry(clock, propsWithTtl(Duration.ofSeconds(60)));

        Minted minted = registry.mint("sess-1", 42L, 55L, 7L, UserRole.USER);
        assertThat(minted.ticket()).isNotBlank();
        assertThat(minted.expiresAt()).isEqualTo(T0.plusSeconds(60));

        Optional<Ticket> redeemed = registry.redeem(minted.ticket());
        assertThat(redeemed).isPresent();
        assertThat(redeemed.get().sessionId()).isEqualTo("sess-1");
        assertThat(redeemed.get().userId()).isEqualTo(42L);
        assertThat(redeemed.get().vmId()).isEqualTo(55L);
        assertThat(redeemed.get().orgId()).isEqualTo(7L);
        assertThat(redeemed.get().userRole()).isEqualTo(UserRole.USER);

        // single-use: a second redeem of the same ticket never succeeds.
        assertThat(registry.redeem(minted.ticket())).isEmpty();
    }

    @Test
    void unknownOrBlankTicketIsRejected() {
        TicketRegistry registry = new TicketRegistry(new TestClock(T0),
                propsWithTtl(Duration.ofSeconds(60)));
        assertThat(registry.redeem("never-minted")).isEmpty();
        assertThat(registry.redeem(null)).isEmpty();
        assertThat(registry.redeem("")).isEmpty();
    }

    @Test
    void expiredTicketIsConsumedButRejected() {
        TestClock clock = new TestClock(T0);
        TicketRegistry registry = new TicketRegistry(clock, propsWithTtl(Duration.ofSeconds(60)));
        Minted minted = registry.mint("sess-2", 1L, 2L, 3L, UserRole.USER);

        clock.advance(Duration.ofSeconds(61));
        assertThat(registry.redeem(minted.ticket())).isEmpty();
        // and it stays consumed — no retry after the clock moves on.
        assertThat(registry.redeem(minted.ticket())).isEmpty();
    }

    @Test
    void capCountsTrackMintAndRedeem() {
        TicketRegistry registry = new TicketRegistry(new TestClock(T0),
                propsWithTtl(Duration.ofSeconds(60)));
        Minted a = registry.mint("s-a", 9L, 100L, 5L, UserRole.USER);
        registry.mint("s-b", 9L, 100L, 5L, UserRole.USER);

        assertThat(registry.countUser(9L)).isEqualTo(2);
        assertThat(registry.countVm(100L)).isEqualTo(2);
        assertThat(registry.countOrg(5L)).isEqualTo(2);

        registry.redeem(a.ticket());
        assertThat(registry.countUser(9L)).isEqualTo(1);
        assertThat(registry.countVm(100L)).isEqualTo(1);
    }

    @Test
    void concurrentRedeemConsumesExactlyOnce() throws Exception {
        TicketRegistry registry = new TicketRegistry(new TestClock(T0),
                propsWithTtl(Duration.ofSeconds(60)));
        // Repeat to make a lost-update race, if any, observable.
        for (int iteration = 0; iteration < 500; iteration++) {
            Minted minted = registry.mint("sess-" + iteration, 1L, 1L, 1L, UserRole.USER);
            AtomicInteger winners = new AtomicInteger();
            int racers = 4;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(racers);
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            for (int r = 0; r < racers; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (registry.redeem(minted.ticket()).isPresent()) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();
            assertThat(winners.get()).as("exactly one redeem wins iteration %d", iteration).isEqualTo(1);
        }
    }
}
