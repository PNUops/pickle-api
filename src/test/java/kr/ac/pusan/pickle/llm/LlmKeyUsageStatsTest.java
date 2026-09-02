package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the key usage response says beside its daily series: the model, error
 * and hour-of-week breakdowns, the latency percentiles, and where the key
 * stands against each of its two budgets.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmKeyUsageStatsTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String token;
    private long keyId;
    private UUID keyPublicId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.findByEmail("llmstats.owner@pusan.ac.kr").orElseGet(() -> {
            User u = new User("llmstats.owner@pusan.ac.kr", "{test-no-login}", "통계키소유자");
            u.setStatus(UserStatus.ACTIVE);
            u.setEmailVerifiedAt(Instant.now());
            return userRepository.save(u);
        });
        token = jwtService.createAccessToken(owner);
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_models");
        // One model per axis, so the token gauge has something to include and
        // something to leave out. A passthrough name has no row at all and is
        // CREDIT by construction.
        jdbcTemplate.update("""
                insert into llm_models (public_name, upstream_ref, upstream_model, budget_axis)
                values ('pickle-general', 'dgx', 'test-model', 'TOKEN')
                """);
        keyId = createIssuedKey(owner.getId());
        keyPublicId = SeedFixtures.publicId(jdbcTemplate, "llm_api_keys", keyId);
    }

    @Test
    void modelsCarryEveryNameThatWasCalledIncludingOnesTheCatalogueDoesNotList() throws Exception {
        // Commercial models are passed through by name and have no catalogue
        // row, so joining the catalogue to build this list would make paid usage
        // disappear from the breakdown entirely.
        event(hoursAgo(2), "pickle-general", "OK", null, 10, 20, 100);
        event(hoursAgo(2), "openai/gpt-4o-mini", "OK", null, 5, 5, 200);
        event(hoursAgo(1), "openai/gpt-4o-mini", "OK", null, 5, 5, 400);

        mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models.length()").value(2))
                .andExpect(jsonPath("$.models[0].modelName").value("openai/gpt-4o-mini"))
                .andExpect(jsonPath("$.models[0].requests").value(2))
                .andExpect(jsonPath("$.models[0].avgLatencyMs").value(300))
                .andExpect(jsonPath("$.models[1].modelName").value("pickle-general"));
    }

    @Test
    void aRequestThatFailedBeforeAModelWasChosenIsItsOwnBucket() throws Exception {
        // Null is a real answer here — dropping these would hide exactly the
        // failures a student most needs to see.
        event(hoursAgo(1), null, "UPSTREAM_ERROR", "model_not_found", 0, 0, 5);

        mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].modelName").doesNotExist())
                .andExpect(jsonPath("$.models[0].failed").value(1))
                .andExpect(jsonPath("$.errorTypes[0].errorType").value("model_not_found"));
    }

    @Test
    void latencyPercentilesCountSuccessfulRequestsOnly() throws Exception {
        // A timeout's duration is the timeout setting and a refusal's is nearly
        // zero: including them moves the numbers without saying anything about
        // how fast the service answers.
        event(hoursAgo(3), "pickle-general", "OK", null, 1, 1, 100);
        event(hoursAgo(3), "pickle-general", "OK", null, 1, 1, 100);
        event(hoursAgo(2), "pickle-general", "TIMEOUT", "upstream_timeout", 0, 0, 60_000);
        event(hoursAgo(1), "pickle-general", "RATE_LIMITED", "quota_exhausted", 0, 0, 1);

        mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latency.samples").value(2))
                .andExpect(jsonPath("$.latency.p99Ms").value(100));
    }

    @Test
    void latencyIsNullRatherThanZeroWhenNothingSucceeded() throws Exception {
        // Three zeroes would read as an instantaneous service.
        event(hoursAgo(1), "pickle-general", "TIMEOUT", "upstream_timeout", 0, 0, 60_000);

        mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latency").doesNotExist());
    }

    @Test
    void theHourGridIsKstAndCarriesOnlyCellsWithTraffic() throws Exception {
        ZonedDateTime when = ZonedDateTime.now(ClockConfig.KST).minusHours(5);
        event(when.toInstant(), "pickle-general", "OK", null, 1, 1, 10);

        mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hourly.length()").value(1))
                .andExpect(jsonPath("$.hourly[0].hour").value(when.getHour()))
                .andExpect(jsonPath("$.hourly[0].weekday")
                        .value(when.getDayOfWeek().getValue()));
    }

    @Test
    void theTokenGaugeCountsSelfServeUsageOnlyLikeTheQuotaSweepDoes() throws Exception {
        // Commercial usage answers to the money limit. Counting it here would
        // put a number on the token gauge that the sweep would never agree
        // with, and one of the two would be refusing requests.
        jdbcTemplate.update("update llm_api_keys set daily_tokens = 1000 where id = ?", keyId);
        // Noon KST today, not "an hour ago": run in the hour after KST midnight
        // and an hour ago is yesterday, and today's gauge would read zero.
        Instant noonToday = LocalDate.now(ClockConfig.KST).atTime(12, 0)
                .atZone(ClockConfig.KST).toInstant();
        event(noonToday, "pickle-general", "OK", null, 100, 100, 50);
        event(noonToday, "openai/gpt-4o-mini", "OK", null, 500, 500, 50);

        mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budget.dailyTokens").value(1000))
                .andExpect(jsonPath("$.budget.todayTokens").value(200))
                .andExpect(jsonPath("$.budget.quotaExhausted").value(false));
    }

    @Test
    void anUnreportedMoneyAxisIsNullAndForecastsNothing() throws Exception {
        mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budget.creditUsage").doesNotExist())
                .andExpect(jsonPath("$.budget.creditDepletionForecast").doesNotExist());
    }

    @Test
    void aSteadySpendOverEnoughDaysProducesADepletionDate() throws Exception {
        // Two readings four days apart: $2 of a $10 limit spent, so the
        // remaining $8 lasts sixteen more days at that rate.
        jdbcTemplate.update("""
                update llm_api_keys set credit_limit = 10, openrouter_usage = 2,
                       openrouter_usage_at = now() where id = ?
                """, keyId);
        // Both readings off one base instant: two calls to now() would put them
        // a few milliseconds more than four days apart, and the rounding up of
        // a fractional day would move the answer.
        Instant base = Instant.now();
        snapshot(base.minusSeconds(4 * 86_400L), "0");
        snapshot(base, "2");

        String body = mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budget.creditUsage").value(2))
                .andReturn().getResponse().getContentAsString();

        LocalDate expected = LocalDate.now(ClockConfig.KST).plusDays(16);
        assertThat(body).contains("\"creditDepletionForecast\":\"" + expected + "\"");
    }

    @Test
    void aWindowResetBetweenReadingsForecastsNothingRatherThanAPhantomSpend() throws Exception {
        // OpenRouter resets a key's reported usage at its limit window, so the
        // figure is not monotone. Reading the smallest and largest amounts
        // instead of the earliest and latest ones would measure a spend across
        // the reset that nobody made, and would inflate the slope.
        jdbcTemplate.update("""
                update llm_api_keys set credit_limit = 10, openrouter_usage = 1,
                       openrouter_usage_at = now() where id = ?
                """, keyId);
        Instant base = Instant.now();
        snapshot(base.minusSeconds(4 * 86_400L), "8");
        snapshot(base, "1");

        mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budget.creditDepletionForecast").doesNotExist());
    }

    @Test
    void tooShortASpanForecastsNothingRatherThanExtrapolatingAnHour() throws Exception {
        // An hour of unusual traffic projected as the standing rate would tell
        // a student their budget dies tomorrow when it does not.
        jdbcTemplate.update("""
                update llm_api_keys set credit_limit = 10, openrouter_usage = 2,
                       openrouter_usage_at = now() where id = ?
                """, keyId);
        snapshot(Instant.now().minusSeconds(3600), "0");
        snapshot(Instant.now(), "2");

        mockMvc.perform(get(usageUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budget.creditDepletionForecast").doesNotExist());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String usageUrl() {
        return "/api/v1/llm-keys/" + keyPublicId + "/usage?days=30";
    }

    private Instant hoursAgo(int hours) {
        return Instant.now().minusSeconds(hours * 3600L);
    }

    private void event(Instant requestedAt, String model, String status, String errorType,
            int in, int out, int latencyMs) {
        jdbcTemplate.update("""
                insert into llm_usage_events (event_id, key_id, public_model_name, status,
                        error_type, input_tokens, output_tokens, estimated, latency_ms, ttft_ms,
                        requested_at)
                values (?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?)
                """, UUID.randomUUID().toString(), keyId, model, status, errorType, in, out,
                latencyMs, latencyMs, java.sql.Timestamp.from(requestedAt));
    }

    private void snapshot(Instant capturedAt, String usage) {
        jdbcTemplate.update("""
                insert into llm_credit_usage_snapshots (key_id, usage_amount, credit_limit,
                        captured_at)
                values (?, ?::numeric, 10, ?)
                """, keyId, usage, java.sql.Timestamp.from(capturedAt));
    }

    private long createIssuedKey(long ownerId) {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "통계 시험 " + unique);
        // A grant alone is not standing: the resolver reads membership first,
        // and without it the key is masked as missing.
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'OWNER'::workspace_member_role)
                """, workspaceId, ownerId);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "통계 시험", "st-" + unique);
        // The money-axis cases below raise this key's credit limit, and a
        // positive limit must name the account funding it.
        long accountId = jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, ?, ?)
                returning id
                """, Long.class, orgId, "통계 시험 사업 " + unique, ownerId);
        long id = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, openrouter_account_id,
                                          created_by)
                values (?, ?, ?, ?, ?, 'pickle-aa', 'ACTIVE'::llm_api_key_status, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "통계 키 " + unique,
                (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", ""),
                accountId, ownerId);
        jdbcTemplate.update("""
                insert into resource_access_grants
                       (resource_type, resource_id, grantee_type, user_id, role)
                values ('LLM_API_KEY', ?, 'USER', ?, 'OWNER'::resource_role)
                """, id, ownerId);
        return id;
    }
}
