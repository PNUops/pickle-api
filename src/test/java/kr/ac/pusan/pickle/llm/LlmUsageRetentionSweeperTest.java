package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
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
