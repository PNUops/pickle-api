package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.jobrunr.server.BackgroundJobServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

/** Direct rollup readiness is proved against the real PostgreSQL watermark. */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmDirectUsageRollupSchedulerTest {

    private static final long ROLLUP_LOCK = 0x50_4B_55_52L;

    @Autowired
    private LlmUsageRollupService rollup;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectProvider<BackgroundJobServer> backgroundServer;

    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void clean() {
        clock.set(Instant.parse("2026-09-29T00:00:00Z"));
        jdbcTemplate.update("delete from llm_usage_daily");
        jdbcTemplate.update("delete from llm_usage_rollup_state");
        jdbcTemplate.update("delete from llm_request_bodies");
        jdbcTemplate.update("delete from llm_usage_events");
    }

    @Test
    void startupBackfillsRawEventsBeforeTheSchedulerCanReportReady() {
        insertRawEvent();
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        try (var scheduler = newScheduler(events)) {
            scheduler.run(new DefaultApplicationArguments(new String[0]));
            long watermark = jdbcTemplate.queryForObject(
                    "select last_event_id from llm_usage_rollup_state", Long.class);
            long rawMax = jdbcTemplate.queryForObject(
                    "select max(id) from llm_usage_events", Long.class);
            assertThat(watermark).isGreaterThanOrEqualTo(rawMax);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from llm_usage_daily", Long.class)).isEqualTo(1);
        }
    }

    @Test
    void startupRefusesAnUnfinishedBackfillWhenAnotherRollupHoldsTheDatabaseLock()
            throws Exception {
        insertRawEvent();
        try (Connection owner = dataSource.getConnection()) {
            assertThat(lock(owner)).isTrue();
            try (var scheduler = newScheduler(mock(ApplicationEventPublisher.class))) {
                assertThatThrownBy(() -> scheduler.run(new DefaultApplicationArguments(new String[0])))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("startup backfill");
            } finally {
                unlock(owner);
            }
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from llm_usage_daily", Long.class))
                .isZero();
    }

    @Test
    void observerRefusesReadinessWhenTheDatabaseSuccessMarkerStopsAdvancing() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        try (var scheduler = newScheduler(events)) {
            scheduler.run(new DefaultApplicationArguments(new String[0]));
            jdbcTemplate.update("update llm_usage_rollup_state set last_success_at = "
                    + "now() - interval '16 minutes'");
            scheduler.observeSafely();
            var refusal = org.mockito.ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
            verify(events).publishEvent(refusal.capture());
            assertThat(refusal.getValue().getState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        }
    }

    @Test
    void persistentWatermarkLagRefusesReadinessEvenWithARecentSuccessMarker() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        try (var scheduler = newScheduler(events)) {
            scheduler.run(new DefaultApplicationArguments(new String[0]));
            insertRawEvent();
            scheduler.observeSafely();
            clock.set(clock.instant().plus(Duration.ofMinutes(16)));
            scheduler.observeSafely();
            var refusal = org.mockito.ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
            verify(events).publishEvent(refusal.capture());
            assertThat(refusal.getValue().getState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        }
    }

    @Test
    void directModeRefusesAnEnabledGenericWorker() {
        var workerOn = new MockEnvironment()
                .withProperty("jobrunr.background-job-server.enabled", "true");
        try (var scheduler = new LlmDirectUsageRollupScheduler(rollup, jdbcTemplate,
                workerOn, backgroundServer, mock(ApplicationEventPublisher.class), clock)) {
            assertThatThrownBy(() -> scheduler.run(new DefaultApplicationArguments(new String[0])))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JobRunr workers off");
        }
    }

    @Test
    void directModeRefusesAnActualBackgroundJobServerBean() {
        @SuppressWarnings("unchecked")
        ObjectProvider<BackgroundJobServer> present = mock(ObjectProvider.class);
        when(present.getIfAvailable()).thenReturn(mock(BackgroundJobServer.class));
        var workerOff = new MockEnvironment()
                .withProperty("jobrunr.background-job-server.enabled", "false");
        try (var scheduler = new LlmDirectUsageRollupScheduler(rollup, jdbcTemplate,
                workerOff, present, mock(ApplicationEventPublisher.class), clock)) {
            assertThatThrownBy(() -> scheduler.run(new DefaultApplicationArguments(new String[0])))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JobRunr workers off");
        }
    }

    private LlmDirectUsageRollupScheduler newScheduler(ApplicationEventPublisher events) {
        var workerOff = new MockEnvironment()
                .withProperty("jobrunr.background-job-server.enabled", "false");
        return new LlmDirectUsageRollupScheduler(rollup, jdbcTemplate, workerOff,
                backgroundServer, events, clock);
    }

    private void insertRawEvent() {
        jdbcTemplate.update("""
                insert into llm_usage_events (event_id, public_model_name, status,
                                              input_tokens, output_tokens, requested_at)
                values (?, 'pickle-test', 'OK', 1, 1, now())
                """, UUID.randomUUID().toString());
    }

    private static boolean lock(Connection connection) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, ROLLUP_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, ROLLUP_LOCK);
            statement.execute();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T00:00:00Z");

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
