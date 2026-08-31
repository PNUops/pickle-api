package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.ac.pusan.pickle.admin.OpenRouterAccountCreditsQueryService;
import kr.ac.pusan.pickle.admin.dto.OpenRouterAccountCreditsResponse;
import kr.ac.pusan.pickle.llm.LlmUsageService;
import kr.ac.pusan.pickle.llm.dto.LlmUsageRequest;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class OpenRouterAccountCreditsTest {

    private static final Instant NOW = Instant.parse("2026-08-31T06:00:00Z");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OpenRouterAccountRepository accountRepository;
    @Autowired private OpenRouterAccountCredentialRepository credentialRepository;
    @Autowired private OpenRouterManagementCredentialCipher cipher;
    @Autowired private OpenRouterPollRepository polls;
    @Autowired private OpenRouterSpendRecorder spendRecorder;
    @Autowired private LlmUsageService usageService;
    @Autowired private OpenRouterPollDispatcher dispatcher;
    @Autowired private OpenRouterCreditRefreshScheduler refreshScheduler;
    @Autowired private PlatformTransactionManager transactionManager;

    private long orgId;
    private long adminId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from openrouter_credit_snapshots");
        jdbcTemplate.update("delete from llm_credit_usage_snapshots");
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_usage_daily");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("""
                delete from request_reviews
                 where request_id in (
                     select id from requests where resource_type = 'LLM_API_KEY')
                """);
        jdbcTemplate.update("delete from llm_key_request_details");
        jdbcTemplate.update("delete from openrouter_account_credentials");
        jdbcTemplate.update("delete from openrouter_accounts");
        jdbcTemplate.update("delete from requests where resource_type = 'LLM_API_KEY'");
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        adminId = SeedFixtures.sysadminId(jdbcTemplate);
    }

    @Test
    void firstPairThenTenMinuteCreditsAndThirtyMinutePairStayOnOneCadence() {
        AccountFixture fixture = activeAccount("사업 A");

        assertThat(polls.dueAccountIds(NOW)).contains(fixture.account().getId());
        OpenRouterPollRepository.Claim first = polls.claim(fixture.account().getId(), NOW);
        assertThat(first.kind()).isEqualTo(OpenRouterPollRepository.PollKind.PAIR);
        assertThat(first.credentialId()).isEqualTo(fixture.credential().getId());
        assertThat(polls.recordPairSuccess(first,
                new OpenRouterClient.Credits(new BigDecimal("100"), new BigDecimal("20")),
                BigDecimal.ZERO, NOW.plusSeconds(2), NOW.plusSeconds(3),
                NOW.plusSeconds(3))).isTrue();

        assertThat(jdbcTemplate.queryForObject("""
                select spend_baseline_total_usage from openrouter_accounts where id = ?
                """, BigDecimal.class, fixture.account().getId())).isEqualByComparingTo("20");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from openrouter_credit_snapshots where account_id = ?
                """, Long.class, fixture.account().getId())).isEqualTo(1);

        Instant tenMinutes = NOW.plusSeconds(3).plusSeconds(600);
        OpenRouterPollRepository.Claim second = polls.claim(
                fixture.account().getId(), tenMinutes);
        assertThat(second.kind()).isEqualTo(OpenRouterPollRepository.PollKind.CREDITS);
        assertThat(polls.recordCreditsSuccess(second,
                new OpenRouterClient.Credits(new BigDecimal("100"), new BigDecimal("22")),
                tenMinutes, tenMinutes)).isTrue();

        Instant thirtyMinutes = NOW.plusSeconds(2).plusSeconds(1800);
        OpenRouterPollRepository.Claim third = polls.claim(
                fixture.account().getId(), thirtyMinutes);
        assertThat(third.kind()).isEqualTo(OpenRouterPollRepository.PollKind.PAIR);
    }

    @Test
    void concurrentDispatchersProduceExactlyOneDurableClaim() throws Exception {
        AccountFixture fixture = activeAccount("사업 동시 claim");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                start.await();
                return polls.claim(fixture.account().getId(), NOW);
            });
            var second = workers.submit(() -> {
                start.await();
                return polls.claim(fixture.account().getId(), NOW);
            });
            start.countDown();
            long claimed = java.util.stream.Stream.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull).count();
            assertThat(claimed).isEqualTo(1);
        }
    }

    @Test
    void triggerDebounceAndTransientBackoffAreDurable() {
        AccountFixture fixture = activeAccount("사업 B");
        assertThat(polls.requestRefresh(fixture.account().getPublicId(), false, NOW)).isTrue();
        assertThat(polls.requestRefresh(fixture.account().getPublicId(), false,
                NOW.plusSeconds(60))).isFalse();

        OpenRouterPollRepository.Claim claim = polls.claim(fixture.account().getId(), NOW);
        polls.recordFailure(claim, OpenRouterPollRepository.FailureAxis.CREDITS,
                OpenRouterCredentialError.THROTTLED, NOW.plusSeconds(1));

        assertThat(jdbcTemplate.queryForObject("""
                select credits_error::text from openrouter_accounts where id = ?
                """, String.class, fixture.account().getId())).isEqualTo("THROTTLED");
        Instant notBefore = jdbcTemplate.queryForObject("""
                select credits_not_before_at from openrouter_accounts where id = ?
                """, java.sql.Timestamp.class, fixture.account().getId()).toInstant();
        assertThat(notBefore).isAfterOrEqualTo(NOW.plusSeconds(601));
        assertThat(polls.claim(fixture.account().getId(), NOW.plusSeconds(300))).isNull();
    }

    @Test
    void claimedImmediateRequestSurvivesAnEnqueueOrWorkerCrash() {
        AccountFixture fixture = activeAccount("사업 durable trigger");
        OpenRouterPollRepository.Claim baseline = polls.claim(fixture.account().getId(), NOW);
        polls.recordPairSuccess(baseline,
                new OpenRouterClient.Credits(new BigDecimal("50"), BigDecimal.ONE),
                BigDecimal.ZERO, NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(2));
        Instant requestedAt = NOW.plusSeconds(62);
        assertThat(polls.requestRefresh(fixture.account().getPublicId(), false, requestedAt))
                .isTrue();
        OpenRouterPollRepository.Claim first = polls.claim(
                fixture.account().getId(), requestedAt);
        polls.abandon(first);

        OpenRouterPollRepository.Claim recovered = polls.claim(
                fixture.account().getId(), requestedAt.plusSeconds(1));

        assertThat(recovered).isNotNull();
        assertThat(recovered.kind()).isEqualTo(OpenRouterPollRepository.PollKind.CREDITS);
        assertThat(recovered.creditsRequestAt()).isEqualTo(requestedAt);
    }

    @Test
    void credentialChangeUpgradesARecentCreditsRequestToFullPair() {
        AccountFixture fixture = activeAccount("사업 full upgrade");
        assertThat(polls.requestRefresh(fixture.account().getPublicId(), false, NOW)).isTrue();
        assertThat(polls.requestAfterCredentialChange(
                fixture.account().getPublicId(), NOW.plusSeconds(60))).isTrue();

        OpenRouterPollRepository.Claim claim = polls.claim(
                fixture.account().getId(), NOW.plusSeconds(60));

        assertThat(claim.kind()).isEqualTo(OpenRouterPollRepository.PollKind.PAIR);
        assertThat(claim.fullRequestAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void creditTriggerWritesTheAccountOnlyAfterTheUsageTransactionCommits() {
        AccountFixture fixture = activeAccount("사업 after commit");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            refreshScheduler.requestCredits(fixture.account().getPublicId());
            assertThat(jdbcTemplate.queryForObject("""
                    select credits_refresh_requested_at is null
                      from openrouter_accounts where id = ?
                    """, Boolean.class, fixture.account().getId())).isTrue();
        });

        assertThat(jdbcTemplate.queryForObject("""
                select credits_refresh_requested_at is not null
                  from openrouter_accounts where id = ?
                """, Boolean.class, fixture.account().getId())).isTrue();
    }

    @Test
    void recentFailurePreservesTheLastSuccessfulValueAndFreshness() {
        AccountFixture fixture = activeAccount("사업 J");
        OpenRouterPollRepository.Claim first = polls.claim(fixture.account().getId(), NOW);
        polls.recordPairSuccess(first,
                new OpenRouterClient.Credits(new BigDecimal("40"), new BigDecimal("5")),
                BigDecimal.ZERO, NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(2));
        Instant retry = NOW.plusSeconds(602);
        OpenRouterPollRepository.Claim second = polls.claim(fixture.account().getId(), retry);
        polls.recordFailure(second, OpenRouterPollRepository.FailureAxis.CREDITS,
                OpenRouterCredentialError.VENDOR_UNAVAILABLE, retry.plusSeconds(1));
        OpenRouterAccountCreditsQueryService query = new OpenRouterAccountCreditsQueryService(
                jdbcTemplate, Clock.fixed(retry.plusSeconds(2), ZoneOffset.UTC));

        OpenRouterAccountCreditsResponse response = query.get(fixture.account());

        assertThat(response.totalCredits()).isEqualByComparingTo("40");
        assertThat(response.totalUsage()).isEqualByComparingTo("5");
        assertThat(response.freshness()).isEqualTo(OpenRouterCreditsFreshness.FRESH);
        assertThat(response.error()).isEqualTo(OpenRouterCredentialError.VENDOR_UNAVAILABLE);
        assertThat(response.lastAttemptAt()).isAfter(response.lastSuccessAt());
    }

    @Test
    void keyBackoffDoesNotBlockAnOverdueCreditsOnlyPoll() {
        AccountFixture fixture = activeAccount("사업 key backoff");
        OpenRouterPollRepository.Claim baseline = polls.claim(fixture.account().getId(), NOW);
        polls.recordPairSuccess(baseline,
                new OpenRouterClient.Credits(new BigDecimal("50"), BigDecimal.ONE),
                BigDecimal.ZERO, NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(2));
        Instant keyDue = NOW.plusSeconds(1801);
        OpenRouterPollRepository.Claim failedPair = polls.claim(
                fixture.account().getId(), keyDue);
        assertThat(failedPair.kind()).isEqualTo(OpenRouterPollRepository.PollKind.PAIR);
        polls.recordFailure(failedPair, OpenRouterPollRepository.FailureAxis.KEYS,
                OpenRouterCredentialError.THROTTLED, keyDue);

        assertThat(polls.dueAccountIds(keyDue.plusSeconds(1)))
                .contains(fixture.account().getId());
        OpenRouterPollRepository.Claim creditsFallback = polls.claim(
                fixture.account().getId(), keyDue.plusSeconds(1));

        assertThat(creditsFallback).isNotNull();
        assertThat(creditsFallback.kind())
                .isEqualTo(OpenRouterPollRepository.PollKind.CREDITS);
        OpenRouterAccountCreditsResponse response = new OpenRouterAccountCreditsQueryService(
                jdbcTemplate, Clock.fixed(keyDue.plusSeconds(1), ZoneOffset.UTC))
                .get(fixture.account());
        assertThat(response.keysError()).isEqualTo(OpenRouterCredentialError.THROTTLED);
        assertThat(response.keysLastAttemptAt()).isEqualTo(keyDue);
    }

    @Test
    void rotatedCredentialCannotPublishAnOldClaim() {
        AccountFixture fixture = activeAccount("사업 C");
        long workspaceId = workspace();
        long requestId = request(workspaceId);
        long keyId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit,
                        openrouter_account_id, openrouter_legacy,
                        openrouter_key_hash, openrouter_key_enc, created_by)
                values (?, ?, ?, 'stale-claim-key', 10, ?, false, 'stale-hash',
                        'ciphertext', ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId,
                fixture.account().getId(), adminId);
        OpenRouterPollRepository.Claim claim = polls.claim(fixture.account().getId(), NOW);
        jdbcTemplate.update("""
                update openrouter_account_credentials
                   set status = 'RETIRING'::openrouter_credential_status,
                       retiring_at = ?
                 where id = ?
                """, java.sql.Timestamp.from(NOW), fixture.credential().getId());
        OpenRouterAccountCredential replacement = new OpenRouterAccountCredential(
                fixture.account().getId(), cipher.encrypt(fixture.account().getPublicId(),
                        "replacement-management-key"), adminId, NOW);
        replacement.activate(NOW);
        credentialRepository.saveAndFlush(replacement);

        assertThat(spendRecorder.recordAccount(java.util.List.of(
                new OpenRouterSpendRecorder.Spend(keyId, new BigDecimal("5"),
                        BigDecimal.TEN, new BigDecimal("5"))),
                NOW.plusSeconds(1), true, claim, () -> { }).persisted()).isFalse();
        assertThat(polls.recordPairSuccess(claim,
                new OpenRouterClient.Credits(BigDecimal.TEN, BigDecimal.ONE),
                BigDecimal.ZERO, NOW.plusSeconds(1), NOW.plusSeconds(2),
                NOW.plusSeconds(2))).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                select credits_total is null from openrouter_accounts where id = ?
                """, Boolean.class, fixture.account().getId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select openrouter_usage is null and openrouter_accounted_usage = 0
                  from llm_api_keys where id = ?
                """, Boolean.class, keyId)).isTrue();
    }

    @Test
    void managedUsageLedgerFlagsResetAndRetainsObservedSegments() {
        AccountFixture fixture = activeAccount("사업 D");
        long workspaceId = workspace();
        long requestId = request(workspaceId);
        long keyId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit,
                        openrouter_account_id, openrouter_legacy,
                        openrouter_key_hash, openrouter_key_enc, created_by)
                values (?, ?, ?, 'ledger-key', 20, ?, false, 'hash-ledger',
                        'ciphertext', ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId,
                fixture.account().getId(), adminId);
        OpenRouterPollRepository.Claim claim = polls.claim(fixture.account().getId(), NOW);

        spendRecorder.recordAccount(java.util.List.of(new OpenRouterSpendRecorder.Spend(
                keyId, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("10"))),
                NOW, false, claim, () -> { });
        spendRecorder.recordAccount(java.util.List.of(new OpenRouterSpendRecorder.Spend(
                keyId, new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5"))),
                NOW.plusSeconds(60), true, claim, () -> { });
        OpenRouterSpendRecorder.AccountRecordResult reset = spendRecorder.recordAccount(
                java.util.List.of(new OpenRouterSpendRecorder.Spend(
                keyId, new BigDecimal("2"), new BigDecimal("20"), new BigDecimal("18"))),
                NOW.plusSeconds(120), true, claim, () -> { });

        assertThat(jdbcTemplate.queryForObject("""
                select openrouter_accounted_usage from llm_api_keys where id = ?
                """, BigDecimal.class, keyId)).isEqualByComparingTo("7");
        assertThat(jdbcTemplate.queryForObject("""
                select openrouter_limit_remaining from llm_api_keys where id = ?
                """, BigDecimal.class, keyId)).isEqualByComparingTo("18");
        assertThat(reset.resetBoundary()).isTrue();
        polls.recordPairSuccess(claim,
                new OpenRouterClient.Credits(new BigDecimal("100"), new BigDecimal("20")),
                new BigDecimal("7"), reset.resetBoundary(), NOW.plusSeconds(121),
                NOW.plusSeconds(122), NOW.plusSeconds(122));
        assertThat(jdbcTemplate.queryForObject("""
                select spend_baseline_invalidated_at is not null
                  from openrouter_accounts where id = ?
                """, Boolean.class, fixture.account().getId())).isTrue();
    }

    @Test
    void unmanagedSpendUsesOnlyTwoCompletedPairedWindows() {
        AccountFixture fixture = activeAccount("사업 I");
        OpenRouterPollRepository.Claim baseline = polls.claim(fixture.account().getId(), NOW);
        polls.recordPairSuccess(baseline,
                new OpenRouterClient.Credits(new BigDecimal("100"), new BigDecimal("20")),
                BigDecimal.ZERO, NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(2));
        Instant next = NOW.plusSeconds(1802);
        OpenRouterPollRepository.Claim pair = polls.claim(fixture.account().getId(), next);
        polls.recordPairSuccess(pair,
                new OpenRouterClient.Credits(new BigDecimal("100"), new BigDecimal("28")),
                new BigDecimal("5"), next.plusSeconds(1), next.plusSeconds(2),
                next.plusSeconds(2));
        OpenRouterAccountCreditsQueryService query = new OpenRouterAccountCreditsQueryService(
                jdbcTemplate, Clock.fixed(next.plusSeconds(2), ZoneOffset.UTC));

        OpenRouterAccountCreditsResponse response = query.get(fixture.account());

        assertThat(response.accountUsageSinceBaseline()).isEqualByComparingTo("8");
        assertThat(response.managedUsageSinceBaseline()).isEqualByComparingTo("5");
        assertThat(response.unmanagedSpend()).isEqualByComparingTo("3");
        assertThat(response.unmanagedSpendUnavailableReason()).isNull();
        assertThat(response.pairedKeysObservedAt()).isEqualTo(next.plusSeconds(1));
        assertThat(response.pairedCreditsObservedAt()).isEqualTo(next.plusSeconds(2));
    }

    @Test
    void accountResetSuppressesOnePairThenStartsANewBaselineSegment() {
        AccountFixture fixture = activeAccount("사업 K");
        OpenRouterPollRepository.Claim baseline = polls.claim(fixture.account().getId(), NOW);
        polls.recordPairSuccess(baseline,
                new OpenRouterClient.Credits(new BigDecimal("100"), new BigDecimal("20")),
                BigDecimal.ZERO, NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(2));
        Instant resetAt = NOW.plusSeconds(1802);
        OpenRouterPollRepository.Claim reset = polls.claim(fixture.account().getId(), resetAt);
        polls.recordPairSuccess(reset,
                new OpenRouterClient.Credits(new BigDecimal("100"), new BigDecimal("10")),
                BigDecimal.ZERO, resetAt.plusSeconds(1), resetAt.plusSeconds(2),
                resetAt.plusSeconds(2));
        OpenRouterAccountCreditsQueryService resetQuery =
                new OpenRouterAccountCreditsQueryService(jdbcTemplate,
                        Clock.fixed(resetAt.plusSeconds(2), ZoneOffset.UTC));
        assertThat(resetQuery.get(fixture.account()).unmanagedSpend()).isNull();
        assertThat(resetQuery.get(fixture.account()).unmanagedSpendUnavailableReason())
                .isEqualTo(OpenRouterUnmanagedSpendUnavailableReason.RESET_BOUNDARY);
        Instant recoveredAt = resetAt.plusSeconds(1802);
        OpenRouterPollRepository.Claim recovered = polls.claim(
                fixture.account().getId(), recoveredAt);
        polls.recordPairSuccess(recovered,
                new OpenRouterClient.Credits(new BigDecimal("100"), new BigDecimal("25")),
                BigDecimal.ZERO, recoveredAt.plusSeconds(1), recoveredAt.plusSeconds(2),
                recoveredAt.plusSeconds(2));
        OpenRouterAccountCreditsQueryService query = new OpenRouterAccountCreditsQueryService(
                jdbcTemplate, Clock.fixed(recoveredAt.plusSeconds(2), ZoneOffset.UTC));

        OpenRouterAccountCreditsResponse response = query.get(fixture.account());

        assertThat(response.unmanagedSpend()).isEqualByComparingTo("15");
        assertThat(response.unmanagedSpendUnavailableReason()).isNull();
    }

    @Test
    void exactThirtyMinutesIsStaleAndIntermediateResetSuppressesForecast() {
        AccountFixture fixture = activeAccount("사업 E");
        jdbcTemplate.update("""
                update openrouter_accounts
                   set credits_total = 50, credits_usage = 110,
                       credits_observed_at = ?, credits_last_success_at = ?,
                       credits_last_attempt_at = ?
                 where id = ?
                """, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), fixture.account().getId());
        snapshot(fixture.account().getId(), NOW.minusSeconds(7 * 86_400L), "100");
        snapshot(fixture.account().getId(), NOW.minusSeconds(3 * 86_400L), "80");
        snapshot(fixture.account().getId(), NOW, "110");

        OpenRouterAccountCreditsQueryService query = new OpenRouterAccountCreditsQueryService(
                jdbcTemplate, Clock.fixed(NOW.plusSeconds(1800), ZoneOffset.UTC));
        OpenRouterAccountCreditsResponse response = query.get(fixture.account());

        assertThat(response.freshness()).isEqualTo(OpenRouterCreditsFreshness.STALE);
        assertThat(response.balance()).isEqualByComparingTo("-60");
        assertThat(response.depletionForecastAt()).isNull();
        assertThat(response.forecastUnavailableReason())
                .isEqualTo(OpenRouterForecastUnavailableReason.RESET_BOUNDARY);
    }

    @Test
    void fortyEightHourFirstLastWindowProducesAnAccountForecast() {
        AccountFixture fixture = activeAccount("사업 G");
        jdbcTemplate.update("""
                update openrouter_accounts
                   set credits_total = 100, credits_usage = 30,
                       credits_observed_at = ?, credits_last_success_at = ?,
                       credits_last_attempt_at = ?
                 where id = ?
                """, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), fixture.account().getId());
        snapshot(fixture.account().getId(), NOW.minusSeconds(2 * 86_400L), "10");
        snapshot(fixture.account().getId(), NOW, "30");
        OpenRouterAccountCreditsQueryService query = new OpenRouterAccountCreditsQueryService(
                jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC));

        OpenRouterAccountCreditsResponse response = query.get(fixture.account());

        assertThat(response.freshness()).isEqualTo(OpenRouterCreditsFreshness.FRESH);
        assertThat(response.averageDailyUsage()).isEqualByComparingTo("10");
        assertThat(response.depletionForecastAt()).isEqualTo(NOW.plusSeconds(7 * 86_400L));
        assertThat(response.forecastUnavailableReason()).isNull();
    }

    @Test
    void neverObservedAccountIsUnknownNotZero() {
        AccountFixture fixture = activeAccount("사업 H");
        OpenRouterAccountCreditsQueryService query = new OpenRouterAccountCreditsQueryService(
                jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC));

        OpenRouterAccountCreditsResponse response = query.get(fixture.account());

        assertThat(response.freshness()).isEqualTo(OpenRouterCreditsFreshness.UNKNOWN);
        assertThat(response.totalCredits()).isNull();
        assertThat(response.totalUsage()).isNull();
        assertThat(response.balance()).isNull();
    }

    @Test
    void onlyANewAcceptedCreditExhaustedEventRequestsOneDebouncedRefresh() {
        AccountFixture fixture = activeAccount("사업 F");
        long workspaceId = workspace();
        long requestId = request(workspaceId);
        UUID keyPublicId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit,
                        openrouter_account_id, openrouter_legacy, created_by)
                values (?, ?, ?, 'trigger-key', 20, ?, false, ?)
                returning public_id
                """, UUID.class, workspaceId, orgId, requestId,
                fixture.account().getId(), adminId);
        LlmUsageRequest.UsageEvent event = new LlmUsageRequest.UsageEvent(
                "credit-trigger-event", 1L, keyPublicId.toString(), "openai/model",
                "CREDIT", "openrouter", 1, "error", "credit_exhausted", 0, 0, false,
                10L, null, NOW.toString());

        assertThat(usageService.ingest(new LlmUsageRequest("test", java.util.List.of(event)))
                .accepted()).isEqualTo(1);
        Instant requested = jdbcTemplate.queryForObject("""
                select credits_refresh_requested_at from openrouter_accounts where id = ?
                """, java.sql.Timestamp.class, fixture.account().getId()).toInstant();
        assertThat(usageService.ingest(new LlmUsageRequest("test", java.util.List.of(event)))
                .duplicates()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select credits_refresh_requested_at from openrouter_accounts where id = ?
                """, java.sql.Timestamp.class, fixture.account().getId()).toInstant())
                .isEqualTo(requested);
        dispatcher.dispatch();
        String jobs = jdbcTemplate.queryForObject("""
                select coalesce(string_agg(jobasjson, ''), '') from jobrunr_jobs
                """, String.class);
        assertThat(jobs).doesNotContain("management-사업 F")
                .doesNotContain("credential_enc");
    }

    private AccountFixture activeAccount(String name) {
        OpenRouterAccount account = accountRepository.saveAndFlush(new OpenRouterAccount(
                orgId, name, null, null, adminId));
        OpenRouterAccountCredential credential = new OpenRouterAccountCredential(account.getId(),
                cipher.encrypt(account.getPublicId(), "management-" + name), adminId, NOW);
        credential.activate(NOW);
        credential = credentialRepository.saveAndFlush(credential);
        return new AccountFixture(account, credential);
    }

    private long workspace() {
        return jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name)
                values ('TEAM'::workspace_kind, ?) returning id
                """, Long.class, "credits-" + UUID.randomUUID());
    }

    private long request(long workspaceId) {
        return jdbcTemplate.queryForObject("""
                insert into requests
                       (resource_type, workspace_id, org_id, requester_id, purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '시험', ?) returning id
                """, Long.class, workspaceId, orgId, adminId,
                "credits-" + UUID.randomUUID());
    }

    private void snapshot(long accountId, Instant observedAt, String usage) {
        jdbcTemplate.update("""
                insert into openrouter_credit_snapshots
                       (account_id, observation_id, total_credits, total_usage,
                        window_started_at, credits_observed_at, window_completed_at)
                values (?, ?, 50, ?, ?, ?, ?)
                """, accountId, UUID.randomUUID(), new BigDecimal(usage),
                java.sql.Timestamp.from(observedAt), java.sql.Timestamp.from(observedAt),
                java.sql.Timestamp.from(observedAt));
    }

    private record AccountFixture(OpenRouterAccount account,
            OpenRouterAccountCredential credential) {
    }
}
