package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.llm.dto.LlmUsageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * A sequence ID is assigned before commit and cannot alone order visible events.
 * The fence protects cooperating API writers, not an older or direct-SQL writer.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmUsageRollupCommitOrderTest {

    private static final long INGEST_FENCE = 0x504b555345564e54L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private LlmUsageRollupService rollup;
    @Autowired
    private LlmUsageService usage;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from llm_usage_daily");
        jdbcTemplate.update("delete from llm_usage_rollup_state");
        jdbcTemplate.update("delete from llm_request_bodies");
        jdbcTemplate.update("delete from llm_usage_events");
    }

    @Test
    void noEventSnapshotStillMarksACompletedRefresh() {
        assertThat(rollup.refresh()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select last_event_id from llm_usage_rollup_state", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select last_success_at from llm_usage_rollup_state", Instant.class))
                .isNotNull();
    }

    @Test
    void aLowerIdCommittingAfterAVisibleHigherIdIsStillRolledUp() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection slow = dataSource.getConnection();
                Connection fast = dataSource.getConnection()) {
            slow.setAutoCommit(false);
            takeFence(slow); // An ingest writer has an uncommitted lower ID.
            long lower = insert(slow);
            long higher = insert(fast); // A prior-version writer committed first.
            assertThat(higher).isGreaterThan(lower);

            Future<Integer> pass = executor.submit(rollup::refresh);
            try {
                // Old code completes and advances the watermark past lower.
                // The fenced snapshot waits for the slow transaction instead.
                pass.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException waitingForFence) {
                // The expected path after the snapshot fence is installed.
            }
            slow.commit();
            pass.get(10, TimeUnit.SECONDS);
            rollup.refresh();

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from llm_usage_events", Long.class)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "select coalesce(sum(requests), 0) from llm_usage_daily", Long.class))
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void usageIngestWaitsForTheSharedFenceBeforeAssigningAnEventId() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            try (PreparedStatement statement =
                    blocker.prepareStatement("select pg_advisory_lock(?)")) {
                statement.setLong(1, INGEST_FENCE);
                statement.execute();
            }
            CountDownLatch entered = new CountDownLatch(1);
            Future<?> ingest = executor.submit(() -> {
                entered.countDown();
                var event = new LlmUsageRequest.UsageEvent(UUID.randomUUID().toString(),
                        null, null, "pickle-test", "TOKEN", null, null, "OK", null,
                        1, 1, false, 1L, 1L, Instant.now().toString(),
                        "chat.completions", null, null, null, null, null, false);
                usage.ingest(new LlmUsageRequest("test", java.util.List.of(event)));
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ingest.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from llm_usage_events", Long.class)).isZero();
            } finally {
                unlock(blocker);
            }
            ingest.get(5, TimeUnit.SECONDS);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from llm_usage_events", Long.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void takeFence(Connection connection) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
            statement.setLong(1, INGEST_FENCE);
            statement.execute();
        }
    }

    private static void unlock(Connection connection) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, INGEST_FENCE);
            statement.execute();
        }
    }

    private static long insert(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into llm_usage_events (event_id, public_model_name, status,
                                              input_tokens, output_tokens, requested_at)
                values (?, 'pickle-test', 'OK', 1, 1, ?) returning id
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setTimestamp(2, Timestamp.from(Instant.parse("2026-09-29T00:00:00Z")));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
