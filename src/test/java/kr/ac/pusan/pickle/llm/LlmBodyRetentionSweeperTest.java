package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The captured-body sweep: a fixed 30 days, on either timestamp, and unaffected
 * by anything that configures the usage sweep.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmBodyRetentionSweeperTest {

    @Autowired
    private LlmBodyRetentionSweeper sweeper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long keyId;

    /**
     * Clears this suite's own table and leaves {@code llm_api_keys} alone.
     *
     * <p>It used to delete the keys too, which made the class order-dependent:
     * keys are the parent of several tables, so whether the delete succeeded
     * depended on whether an earlier class in the same embedded Postgres had
     * left rows in one of them. It passed alone and failed in a full run.
     *
     * <p>Adding the missing children to the list would fix today's failure and
     * rot the same way the moment another table references a key. Nothing here
     * needs the keys gone: every assertion counts rows in
     * {@code llm_request_bodies}, which this does clear, and each test inserts
     * its own key.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from llm_request_bodies");
        jdbcTemplate.update("delete from settings where key = ?",
                SettingsService.LLM_USAGE_RETENTION_DAYS);
        keyId = insertKey();
    }

    @Test
    void deletesPastThirtyDaysAndKeepsWhatIsInside() {
        insertBody("evt-old", daysAgo(31), daysAgo(31));
        insertBody("evt-edge", daysAgo(29), daysAgo(29));

        sweeper.sweep();

        assertThat(eventIds()).containsExactly("evt-edge");
    }

    @Test
    void aFutureRequestedAtCannotOutliveItsArrival() {
        // The reason the predicate names both columns. A gateway with a skewed
        // clock reports a time that is never older than the cutoff, and on
        // requested_at alone that row would sit there forever.
        insertBody("evt-skewed", Instant.now().plus(400, ChronoUnit.DAYS), daysAgo(31));
        insertBody("evt-fresh", Instant.now().plus(400, ChronoUnit.DAYS), Instant.now());

        sweeper.sweep();

        assertThat(eventIds()).containsExactly("evt-fresh");
    }

    @Test
    void aBacklogLargerThanOneBatchIsClearedInASingleSweep() {
        // Pins the loop's exit condition: it must continue while a full batch
        // came back, not merely while something did.
        for (int i = 0; i < 1001; i++) {
            insertBody("evt-" + i, daysAgo(40), daysAgo(40));
        }

        sweeper.sweep();

        assertThat(bodyCount()).isZero();
    }

    @Test
    void theUsageRetentionSettingDoesNotReachThisSweep() {
        // The 30 days is fixed. A future refactor that "unifies" the two
        // retentions behind the existing setting would fail here.
        jdbcTemplate.update("""
                insert into settings (key, value) values (?, '365')
                on conflict (key) do update set value = excluded.value
                """, SettingsService.LLM_USAGE_RETENTION_DAYS);
        insertBody("evt-old", daysAgo(31), daysAgo(31));

        sweeper.sweep();

        assertThat(bodyCount()).isZero();
    }

    @Test
    void anEmptyTableIsAQuietSuccess() {
        sweeper.sweep();

        assertThat(bodyCount()).isZero();
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private long bodyCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from llm_request_bodies", Long.class);
    }

    private java.util.List<String> eventIds() {
        return jdbcTemplate.queryForList(
                "select event_id from llm_request_bodies order by event_id", String.class);
    }

    private void insertBody(String eventId, Instant requestedAt, Instant receivedAt) {
        jdbcTemplate.update("""
                insert into llm_request_bodies (public_id, event_id, key_id, request_enc,
                        cipher_key_id, requested_at, received_at)
                values (?, ?, ?, 'llmb-v1:v1:aa:bb', 'v1', ?, ?)
                """, UUID.randomUUID(), eventId, keyId,
                java.sql.Timestamp.from(requestedAt), java.sql.Timestamp.from(receivedAt));
    }

    private long insertKey() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "body-retention " + unique);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "body-retention", "rt-" + unique);
        return jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, created_by)
                values (?, ?, ?, ?, ?, 'pickle-aa', 'ACTIVE'::llm_api_key_status, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + unique,
                String.format("%064x", requestId), ownerId);
    }
}
