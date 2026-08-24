package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Daily token quotas: the api counts, the gateway refuses.
 *
 * <p>Two properties carry the whole feature and both fail silently. The flag
 * must move the <b>generation</b> — without that the gateway is never handed a
 * document and enforces nothing, while the database looks entirely correct.
 * And an unchanged sweep must <b>not</b> move it, or the gateway is handed a
 * full document every five minutes forever.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, LlmDailyQuotaTest.FixedClockConfig.class})
class LlmDailyQuotaTest {

    /** Mid-afternoon KST, comfortably inside one calendar day. */
    private static final Instant NOON_KST = Instant.parse("2026-08-11T03:00:00Z");

    static final class MutableClock extends Clock {
        private volatile Instant now = NOON_KST;

        void set(Instant instant) {
            this.now = instant;
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

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private LlmQuotaService quotaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private long keyId;

    @BeforeEach
    void setUp() {
        ((MutableClock) clock).set(NOON_KST);
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("delete from llm_models");
        // The allowance counts only TOKEN-axis models' events, so the fixture
        // carries one model per axis and every metered event names one.
        jdbcTemplate.update("""
                insert into llm_models (public_name, upstream_ref, upstream_model, budget_axis)
                values ('pnu-test', 'openai', 'test-model', 'TOKEN'),
                       ('vendor-test', 'openrouter', 'vendor-test', 'CREDIT')
                """);
        keyId = insertKeyWithDailyLimit(1_000L);
        jdbcTemplate.update("""
                insert into llm_gateway_state (id, generation, service_enabled)
                values (true, 1, true)
                on conflict (id) do update set generation = 1
                """);
    }

    @Test
    void aKeyUnderItsAllowanceIsUntouchedAndSpendsNoGeneration() {
        recordUsage(keyId, 400, 300, NOON_KST);
        long before = generation();

        assertThat(quotaService.refresh()).isZero();

        assertThat(exhausted(keyId)).isFalse();
        assertThat(generation())
                .as("an unchanged sweep must not hand the gateway a new document")
                .isEqualTo(before);
    }

    @Test
    void crossingTheAllowanceFlipsTheFlagAndMovesTheGeneration() {
        recordUsage(keyId, 600, 500, NOON_KST);
        long before = generation();

        assertThat(quotaService.refresh()).isEqualTo(1);

        assertThat(exhausted(keyId)).isTrue();
        assertThat(generation())
                .as("without a bump the gateway is never handed the refusal, and "
                        + "the database looks correct while nothing is enforced")
                .isGreaterThan(before);
    }

    @Test
    void aSecondSweepOverTheSameStateChangesNothing() {
        recordUsage(keyId, 1_200, 0, NOON_KST);
        quotaService.refresh();
        long after = generation();

        assertThat(quotaService.refresh()).isZero();
        assertThat(generation()).isEqualTo(after);
    }

    @Test
    void theNextCalendarDayReleasesTheKeyWithoutAnySeparateReset() {
        recordUsage(keyId, 1_200, 0, NOON_KST);
        quotaService.refresh();
        assertThat(exhausted(keyId)).isTrue();

        // 00:30 KST the following day. Yesterday's events are outside the
        // window, so the ordinary sweep is the whole reset mechanism.
        ((MutableClock) clock).set(Instant.parse("2026-08-11T15:30:00Z"));

        assertThat(quotaService.refresh()).isEqualTo(1);
        assertThat(exhausted(keyId)).isFalse();
    }

    @Test
    void usageThatNeverResolvedToAKeyIsChargedToNobody() {
        // The gateway keeps these rows: they are the trace of a client looping
        // on a bad key. Charging them to a key would let one person's mistake
        // exhaust somebody else's allowance.
        recordUsage(null, 5_000, 5_000, NOON_KST);

        assertThat(quotaService.refresh()).isZero();
        assertThat(exhausted(keyId)).isFalse();
    }

    @Test
    void aKeyWithNoAllowanceIsNeverFlagged() {
        long unlimited = insertKeyWithDailyLimit(null);
        recordUsage(unlimited, 500_000, 500_000, NOON_KST);

        assertThat(quotaService.refresh()).isZero();
        assertThat(exhausted(unlimited)).isFalse();
    }

    @Test
    void creditAxisUsageNeverDrainsTheTokenAllowance() {
        // Commercial usage answers to the key's money limit; summing it here
        // would let paid traffic exhaust the self-serve budget — the exact
        // contamination the model-side axis exists to prevent. A passthrough
        // model name (absent from llm_models) is CREDIT by construction and
        // must stay outside the sum the same way.
        recordUsage(keyId, "vendor-test", 500_000, 500_000, NOON_KST);
        recordUsage(keyId, "vendor/passthrough-model", 500_000, 500_000, NOON_KST);

        assertThat(quotaService.refresh()).isZero();
        assertThat(exhausted(keyId)).isFalse();

        // Token-axis usage on top still counts, on the same key.
        recordUsage(keyId, 600, 500, NOON_KST);
        assertThat(quotaService.refresh()).isEqualTo(1);
        assertThat(exhausted(keyId)).isTrue();
    }

    @Test
    void aZeroAllowanceClosesTheTokenAxisWithoutAnyUsage() {
        // 0 means "the token axis is unusable" (distinct from null = no daily
        // limit), so the flag must flip on the very first sweep, before any
        // event exists for the key.
        long closed = insertKeyWithDailyLimit(0L);

        assertThat(quotaService.refresh()).isEqualTo(1);
        assertThat(exhausted(closed)).isTrue();
        assertThat(exhausted(keyId)).isFalse();
    }

    private long insertKeyWithDailyLimit(Long dailyTokens) {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "일일 한도 시험 " + unique);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "일일 한도 시험", "llm-" + unique);
        return jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, daily_tokens, created_by)
                values (?, ?, ?, ?, ?, 'pickle-aa', 'ACTIVE'::llm_api_key_status, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + requestId,
                String.format("%064x", requestId), dailyTokens, ownerId);
    }

    private void recordUsage(Long forKeyId, int input, int output, Instant when) {
        recordUsage(forKeyId, "pnu-test", input, output, when);
    }

    private void recordUsage(Long forKeyId, String modelName, int input, int output,
            Instant when) {
        jdbcTemplate.update("""
                insert into llm_usage_events (event_id, key_id, public_model_name, status,
                                              input_tokens, output_tokens, requested_at)
                values (?, ?, ?, 'OK', ?, ?, ?)
                """, UUID.randomUUID().toString(), forKeyId, modelName, input, output,
                java.time.OffsetDateTime.ofInstant(when, ZoneId.of("Asia/Seoul")));
    }

    private boolean exhausted(long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select quota_exhausted from llm_api_keys where id = ?", Boolean.class, id));
    }

    private long generation() {
        return jdbcTemplate.queryForObject(
                "select generation from llm_gateway_state where id", Long.class);
    }
}
