package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The daily rollup: KST bucketing, the backfill from an absent watermark, and
 * the property the whole design turns on — a day that gains a late event is
 * recomputed rather than added to.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmUsageRollupTest {

    @Autowired
    private LlmUsageRollupService rollupService;
    @Autowired
    private LlmUsageRetentionSweeper retentionSweeper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long keyId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from llm_usage_daily");
        jdbcTemplate.update("delete from llm_usage_rollup_state");
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("delete from settings where key = ?",
                SettingsService.LLM_USAGE_RETENTION_DAYS);
        keyId = insertKey();
    }

    @Test
    void anAbsentWatermarkMakesTheFirstRefreshTheBackfill() {
        // No state row is the fresh-database state, and it must not be a
        // special case anybody has to seed: it reads as zero, so the first run
        // aggregates everything that already exists.
        insertEvent("2026-08-20T03:00:00Z", "pickle-general", "OK", 10, 20, 100);
        insertEvent("2026-08-20T04:00:00Z", "pickle-general", "OK", 5, 5, 50);

        assertThat(rollupService.currentWatermark()).isZero();
        assertThat(rollupService.refresh()).isEqualTo(1);

        Map<String, Object> bucket = onlyBucket();
        assertThat(bucket.get("requests")).isEqualTo(2L);
        assertThat(bucket.get("input_tokens")).isEqualTo(15L);
        assertThat(bucket.get("output_tokens")).isEqualTo(25L);
        assertThat(bucket.get("latency_ms_sum")).isEqualTo(150L);
        assertThat(rollupService.currentWatermark()).isPositive();
    }

    @Test
    void daysAreKstCalendarDaysSoAUtcMidnightStraddleDoesNotSplitThem() {
        // 2026-08-20T16:00Z is already 2026-08-21 in KST, and 23:00Z is the
        // same KST day as 15:00Z is not. Bucketing by UTC would put these two
        // on different days and split one afternoon's work in half.
        insertEvent("2026-08-20T16:00:00Z", "pickle-general", "OK", 1, 1, 10);
        insertEvent("2026-08-20T23:30:00Z", "pickle-general", "OK", 1, 1, 10);

        rollupService.refresh();

        assertThat(jdbcTemplate.queryForList("select day::text from llm_usage_daily order by day",
                String.class)).containsExactly("2026-08-21");
    }

    @Test
    void aLateEventRecomputesItsDayInsteadOfBeingAddedToIt() {
        // Batches do not arrive in time order, so a day the rollup has already
        // written can still gain events. The rebuild is what keeps the total
        // independent of arrival order; an incremental add would double-count
        // the moment the same day is touched twice.
        insertEvent("2026-08-20T03:00:00Z", "pickle-general", "OK", 10, 10, 100);
        rollupService.refresh();
        assertThat(onlyBucket().get("requests")).isEqualTo(1L);

        insertEvent("2026-08-20T05:00:00Z", "pickle-general", "OK", 3, 3, 30);
        assertThat(rollupService.refresh()).isEqualTo(1);

        Map<String, Object> bucket = onlyBucket();
        assertThat(bucket.get("requests")).isEqualTo(2L);
        assertThat(bucket.get("input_tokens")).isEqualTo(13L);
    }

    @Test
    void statusesAreCountedTheSameWayTheKeySeriesCountsThem() {
        // OK and RATE_LIMITED are named; anything else is a failure — including
        // a status this code has never seen, which is still a request that
        // happened and must not vanish from the counts.
        insertEvent("2026-08-20T03:00:00Z", "pickle-general", "OK", 1, 1, 10);
        insertEvent("2026-08-20T03:10:00Z", "pickle-general", "RATE_LIMITED", 0, 0, 5);
        insertEvent("2026-08-20T03:20:00Z", "pickle-general", "UPSTREAM_ERROR", 0, 0, 7);
        insertEvent("2026-08-20T03:30:00Z", "pickle-general", "SOMETHING_NEW", 0, 0, 3);

        rollupService.refresh();

        Map<String, Object> bucket = onlyBucket();
        assertThat(bucket.get("requests")).isEqualTo(4L);
        assertThat(bucket.get("succeeded")).isEqualTo(1L);
        assertThat(bucket.get("rate_limited")).isEqualTo(1L);
        assertThat(bucket.get("failed")).isEqualTo(2L);
    }

    @Test
    void unattributedAndModellessEventsGetTheirOwnBucketsAndSurviveARebuild() {
        // A null key and a null model are real buckets, not unknowns: traffic
        // that never resolved to a key, and requests that failed before a model
        // was chosen. The rebuild must collide with its own previous row for
        // those too, or a second refresh silently doubles them.
        insertEventForKey(null, "2026-08-20T03:00:00Z", null, "AUTH_FAILED", 0, 0, 4);
        insertEvent("2026-08-20T03:05:00Z", null, "MODEL_NOT_FOUND", 0, 0, 6);

        rollupService.refresh();
        jdbcTemplate.update("delete from llm_usage_rollup_state");
        rollupService.refresh();

        List<Map<String, Object>> buckets = jdbcTemplate.queryForList(
                "select key_id, public_model_name, requests from llm_usage_daily");
        assertThat(buckets).hasSize(2);
        assertThat(buckets).allSatisfy(row -> assertThat(row.get("requests")).isEqualTo(1L));
    }

    @Test
    void aRefreshWithNothingNewDoesNoWork() {
        insertEvent("2026-08-20T03:00:00Z", "pickle-general", "OK", 1, 1, 10);
        rollupService.refresh();

        assertThat(rollupService.refresh()).isZero();
    }

    @Test
    void aSweptDayIsFrozenSoAResentEventCannotRepaintItWithAFragment() {
        // The sweep deletes raw events; after that the rollup row is the only
        // record of that day. A lost checkpoint makes the gateway re-send old
        // events, and recomputing the day from the handful that came back would
        // replace a complete bucket with a fragment.
        // The day is aggregated first, the way it happens in life: the events
        // are fresh when the rollup sees them, and only later does retention
        // catch up with them.
        insertEvent("2020-01-01T03:00:00Z", "pickle-general", "OK", 100, 100, 100);
        insertEvent("2020-01-01T04:00:00Z", "pickle-general", "OK", 100, 100, 100);
        rollupService.refresh();
        assertThat(onlyBucket().get("requests")).isEqualTo(2L);

        jdbcTemplate.update("""
                insert into settings (key, value) values (?, '90'::jsonb)
                on conflict (key) do update set value = excluded.value
                """, SettingsService.LLM_USAGE_RETENTION_DAYS);

        retentionSweeper.sweep();
        assertThat(jdbcTemplate.queryForObject("select count(*) from llm_usage_events", Long.class))
                .isZero();

        // The re-send: one of the two events comes back.
        insertEvent("2020-01-01T03:00:00Z", "pickle-general", "OK", 100, 100, 100);
        rollupService.refresh();

        assertThat(onlyBucket().get("requests")).isEqualTo(2L);
    }

    @Test
    void turningRetentionOffDoesNotThawADayWhoseEventsAreAlreadyGone() {
        // The freeze follows what the sweep actually did, not what the setting
        // now says. Deleted events do not come back when retention is switched
        // off, so a day thawed by that switch would be rebuilt from whatever
        // fragment a re-send brought — replacing a complete bucket.
        insertEvent("2020-01-01T03:00:00Z", "pickle-general", "OK", 100, 100, 100);
        insertEvent("2020-01-01T04:00:00Z", "pickle-general", "OK", 100, 100, 100);
        rollupService.refresh();
        jdbcTemplate.update("""
                insert into settings (key, value) values (?, '90'::jsonb)
                on conflict (key) do update set value = excluded.value
                """, SettingsService.LLM_USAGE_RETENTION_DAYS);
        retentionSweeper.sweep();

        // Retention goes back off, and a lost checkpoint re-sends one event.
        jdbcTemplate.update("delete from settings where key = ?",
                SettingsService.LLM_USAGE_RETENTION_DAYS);
        insertEvent("2020-01-01T03:00:00Z", "pickle-general", "OK", 100, 100, 100);
        rollupService.refresh();

        assertThat(onlyBucket().get("requests")).isEqualTo(2L);
    }

    private Map<String, Object> onlyBucket() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("select * from llm_usage_daily");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void insertEvent(String requestedAt, String model, String status, int in, int out,
            int latencyMs) {
        insertEventForKey(keyId, requestedAt, model, status, in, out, latencyMs);
    }

    private void insertEventForKey(Long key, String requestedAt, String model, String status,
            int in, int out, int latencyMs) {
        jdbcTemplate.update("""
                insert into llm_usage_events (event_id, key_id, public_model_name, status,
                        input_tokens, output_tokens, estimated, latency_ms, ttft_ms, requested_at)
                values (?, ?, ?, ?, ?, ?, false, ?, ?, ?::timestamptz)
                """, UUID.randomUUID().toString(), key, model, status, in, out, latencyMs,
                latencyMs, requestedAt);
    }

    private long insertKey() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "롤업 시험 " + unique);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "롤업 시험", "ru-" + unique);
        return jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, created_by, created_at)
                values (?, ?, ?, ?, ?, 'pickle-aa', 'ACTIVE'::llm_api_key_status, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + unique,
                String.format("%064x", requestId), ownerId,
                java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
    }
}
