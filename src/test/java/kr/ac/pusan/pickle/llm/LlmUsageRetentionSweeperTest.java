package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The usage-event sweep: off unless configured, and never ahead of the rollup.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmUsageRetentionSweeperTest {

    @Autowired
    private LlmUsageRetentionSweeper sweeper;
    @Autowired
    private LlmUsageRollupService rollupService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    private long keyId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from llm_usage_daily");
        jdbcTemplate.update("delete from llm_usage_rollup_state");
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_request_bodies");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("delete from settings where key = ?",
                SettingsService.LLM_USAGE_RETENTION_DAYS);
        keyId = insertKey();
    }

    @Test
    void withNoRetentionConfiguredNothingIsEverDeleted() {
        // The default. These rows are the only raw record of who called what,
        // so keeping them is what happens until somebody decides otherwise.
        insertEvent("2019-01-01T03:00:00Z");
        rollupService.refresh();

        sweeper.sweep();

        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    void aConfiguredRetentionDeletesOldEventsAndKeepsRecentOnes() {
        configureRetention(90);
        insertEvent("2019-01-01T03:00:00Z");
        insertEvent(Instant.now().toString());
        rollupService.refresh();

        sweeper.sweep();

        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    void anEventTheRollupHasNotSeenSurvivesEvenWhenItIsOldEnoughToGo() {
        // Deleting between arrival and aggregation would leave the event in no
        // record at all — not in the raw table, not in the rollup.
        configureRetention(90);
        insertEvent("2019-01-01T03:00:00Z");

        sweeper.sweep();

        assertThat(eventCount()).isEqualTo(1);

        // And it must still be aggregatable afterwards. A sweep that deleted
        // nothing must not mark the day gone: the rollup would then skip it as
        // frozen, advance its watermark past the event, and the NEXT sweep --
        // no longer blocked by that watermark -- would delete an event that had
        // never been counted anywhere.
        rollupService.refresh();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from llm_usage_daily", Long.class)).isEqualTo(1L);
        sweeper.sweep();
        assertThat(eventCount()).isZero();
    }

    @Test
    void aLateEventKeepsItsEntireExpiredDayUntilRollupRebuildsIt() {
        configureRetention(90);
        insertEvent("2019-01-01T03:00:00Z");
        rollupService.refresh();
        insertEvent("2019-01-01T04:00:00Z");

        sweeper.sweep();

        assertThat(eventCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select swept_before from llm_usage_rollup_state", java.time.LocalDate.class))
                .isEqualTo(java.time.LocalDate.of(2019, 1, 1));
        rollupService.refresh();
        assertThat(dailyRequests()).isEqualTo(2);

        sweeper.sweep();
        assertThat(eventCount()).isZero();
        assertThat(dailyRequests()).isEqualTo(2);
    }

    @Test
    void frozenPrefixSurvivesASecondBatchFailureAndAnOldDayResend() {
        configureRetention(90);
        jdbcTemplate.update("""
                insert into llm_usage_events (event_id, key_id, public_model_name, status,
                        input_tokens, output_tokens, requested_at)
                select gen_random_uuid()::text, ?, 'pickle-general', 'OK', 1, 1,
                       '2019-01-01T03:00:00Z'::timestamptz
                  from generate_series(1, 1205)
                """, keyId);
        long tail = jdbcTemplate.queryForObject("select max(id) from llm_usage_events", Long.class);
        rollupService.refresh();
        assertThat(dailyRequests()).isEqualTo(1205);
        jdbcTemplate.execute("""
                create function fail_tail_usage_delete() returns trigger language plpgsql as $$
                begin
                  if old.id = %d then
                    raise exception 'synthetic second batch failure';
                  end if;
                  return old;
                end $$
                """.formatted(tail));
        jdbcTemplate.execute("""
                create trigger fail_tail_usage_delete before delete on llm_usage_events
                for each row execute function fail_tail_usage_delete()
                """);
        try {
            assertThatThrownBy(sweeper::sweep).isInstanceOf(RuntimeException.class);
            assertThat(eventCount()).isEqualTo(205);
            assertThat(jdbcTemplate.queryForObject(
                    "select swept_before from llm_usage_rollup_state", java.time.LocalDate.class))
                    .isAfter(java.time.LocalDate.of(2019, 1, 1));
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_tail_usage_delete on llm_usage_events");
            jdbcTemplate.execute("drop function if exists fail_tail_usage_delete()");
        }
        insertEvent("2019-01-01T05:00:00Z");
        rollupService.refresh();
        assertThat(dailyRequests()).isEqualTo(1205);
        sweeper.sweep();
        assertThat(eventCount()).isZero();
        assertThat(dailyRequests()).isEqualTo(1205);
    }

    @Test
    void lockContentionIsAVisibleFailureWithoutFreezeOrDelete() throws Exception {
        configureRetention(90);
        insertEvent("2019-01-01T03:00:00Z");
        rollupService.refresh();
        insertEvent("2019-01-01T04:00:00Z");
        try (Connection owner = dataSource.getConnection()) {
            advisory(owner, "select pg_advisory_lock(?)", 0x50_4B_55_52L);
            try {
                assertThat(rollupService.refresh()).isZero();
                assertThatThrownBy(sweeper::sweep)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must retry after lock contention");
                assertThat(eventCount()).isEqualTo(2);
                assertThat(dailyRequests()).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject(
                        "select swept_before from llm_usage_rollup_state", java.time.LocalDate.class))
                        .isNull();
            } finally {
                advisory(owner, "select pg_advisory_unlock(?)", 0x50_4B_55_52L);
            }
        }
        rollupService.refresh();
        sweeper.sweep();
        assertThat(dailyRequests()).isEqualTo(2);
        assertThat(eventCount()).isZero();
    }

    @Test
    void freezeWaitsForAnIngestCommitFence() throws Exception {
        configureRetention(90);
        insertEvent("2019-01-01T03:00:00Z");
        rollupService.refresh();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection ingest = dataSource.getConnection()) {
            ingest.setAutoCommit(false);
            advisory(ingest, "select pg_advisory_xact_lock(?)", 0x504b555345564e54L);
            var sweep = executor.submit(sweeper::sweep);
            try {
                assertThatThrownBy(() -> sweep.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(eventCount()).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject(
                        "select swept_before from llm_usage_rollup_state", java.time.LocalDate.class))
                        .isNull();
            } finally {
                ingest.commit();
            }
            sweep.get(5, TimeUnit.SECONDS);
            assertThat(eventCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void boundedDeletionReleasesTheIngestFenceAfterTheFreezeMark() throws Exception {
        configureRetention(90);
        insertEvent("2019-01-01T03:00:00Z");
        rollupService.refresh();
        jdbcTemplate.execute("""
                create function delay_usage_delete() returns trigger language plpgsql as $$
                begin
                  perform pg_sleep(2);
                  return old;
                end $$
                """);
        jdbcTemplate.execute("""
                create trigger delay_usage_delete before delete on llm_usage_events
                for each row execute function delay_usage_delete()
                """);
        var executor = Executors.newSingleThreadExecutor();
        var sweep = executor.submit(sweeper::sweep);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (jdbcTemplate.queryForObject("select max(swept_before) "
                    + "from llm_usage_rollup_state", java.time.LocalDate.class) == null
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(jdbcTemplate.queryForObject("select max(swept_before) "
                    + "from llm_usage_rollup_state", java.time.LocalDate.class)).isNotNull();
            try (Connection ingest = dataSource.getConnection()) {
                ingest.setAutoCommit(false);
                assertThat(tryFence(ingest)).isTrue();
                ingest.commit();
            }
            sweep.get(5, TimeUnit.SECONDS);
            assertThat(eventCount()).isZero();
        } finally {
            executor.shutdownNow();
            try {
                sweep.get(5, TimeUnit.SECONDS);
            } finally {
                jdbcTemplate.execute("drop trigger if exists delay_usage_delete on llm_usage_events");
                jdbcTemplate.execute("drop function if exists delay_usage_delete()");
            }
        }
    }

    @Test
    void lengtheningOrDisablingRetentionDoesNotDeleteSurvivingFrozenRaw() {
        configureRetention(90);
        insertEvent(Instant.now().minus(java.time.Duration.ofDays(100)).toString());
        rollupService.refresh();
        jdbcTemplate.execute("""
                create function fail_all_usage_delete() returns trigger language plpgsql as $$
                begin
                  raise exception 'synthetic deletion interruption';
                end $$
                """);
        jdbcTemplate.execute("""
                create trigger fail_all_usage_delete before delete on llm_usage_events
                for each row execute function fail_all_usage_delete()
                """);
        try {
            assertThatThrownBy(sweeper::sweep).isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_all_usage_delete on llm_usage_events");
            jdbcTemplate.execute("drop function if exists fail_all_usage_delete()");
        }
        assertThat(eventCount()).isEqualTo(1);
        configureRetention(365);
        sweeper.sweep();
        assertThat(eventCount()).isEqualTo(1);
        configureRetention(0);
        sweeper.sweep();
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    void aValueBelowTheFloorIsReadAsTheFloorRatherThanObeyed() {
        // The settings validator refuses these, so one can only arrive by hand.
        // Obeying it would delete events the gateway may still re-send, which
        // is how a re-sent event gets counted twice.
        configureRetention(1);
        insertEvent(Instant.now().minus(java.time.Duration.ofDays(30)).toString());
        rollupService.refresh();

        sweeper.sweep();

        assertThat(eventCount()).isEqualTo(1);
    }

    private void configureRetention(int days) {
        jdbcTemplate.update("""
                insert into settings (key, value) values (?, ?::jsonb)
                on conflict (key) do update set value = excluded.value
                """, SettingsService.LLM_USAGE_RETENTION_DAYS, String.valueOf(days));
    }

    private long eventCount() {
        return jdbcTemplate.queryForObject("select count(*) from llm_usage_events", Long.class);
    }

    private long dailyRequests() {
        return jdbcTemplate.queryForObject(
                "select coalesce(sum(requests), 0) from llm_usage_daily", Long.class);
    }

    private static void advisory(Connection connection, String sql, long key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private static boolean tryFence(Connection connection) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, 0x504b555345564e54L);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void insertEvent(String requestedAt) {
        jdbcTemplate.update("""
                insert into llm_usage_events (event_id, key_id, public_model_name, status,
                        input_tokens, output_tokens, estimated, latency_ms, ttft_ms, requested_at)
                values (?, ?, 'pickle-general', 'OK', 1, 1, false, 10, 10, ?::timestamptz)
                """, UUID.randomUUID().toString(), keyId, requestedAt);
    }

    private long insertKey() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('PROJECT'::workspace_kind, ?)
                returning id
                """, Long.class, "보존 시험 " + unique);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "보존 시험", "rt-" + unique);
        return jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, created_by)
                values (?, ?, ?, ?, ?, 'pickle-aa', 'ACTIVE'::llm_api_key_status, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + unique,
                String.format("%064x", requestId), ownerId);
    }
}
