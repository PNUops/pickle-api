package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class OpenRouterAllocationQueryTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OpenRouterAccountRepository accountRepository;
    @Autowired private OpenRouterAllocationQuery allocationQuery;

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

    /**
     * The incident this exists for: thirty approved course keys, none issued
     * yet, on an account that reads as untouched. Approval is what commits the
     * money, so a key that has not been minted still counts against the balance.
     */
    @Test
    void pendingKeysCountBecauseApprovalIsWhatCommitsTheMoney() {
        long account = account("pending-counts");
        for (int i = 0; i < 30; i++) {
            key(account, "10", null, "PENDING", null, null, null);
        }

        OpenRouterAllocationQuery.Allocation allocation = allocationQuery.forAccount(account);

        assertThat(allocation.committedCreditLimit()).isEqualByComparingTo("300");
        assertThat(allocation.committedKeyCount()).isEqualTo(30);
        assertThat(allocation.awaitingProvisionKeyCount()).isEqualTo(30);
        assertThat(allocation.remainingCommitment()).isEqualByComparingTo("300");
    }

    /**
     * A total cap goes out once and a window limit refills, so the two are
     * reported apart. They are still added together in the total: what can leave
     * this balance before the next reset is the whole of both.
     */
    @Test
    void windowLimitsAreReportedApartAndCountedInTheTotal() {
        long account = account("windows");
        key(account, "10", null, "ACTIVE", "hash-cap", null, null);
        key(account, "20", "MONTHLY", "ACTIVE", "hash-monthly", null, null);
        key(account, "5", "DAILY", "ACTIVE", "hash-daily", null, null);
        key(account, "1", "WEEKLY", "ACTIVE", "hash-weekly", null, null);

        OpenRouterAllocationQuery.Allocation allocation = allocationQuery.forAccount(account);

        assertThat(allocation.committedTotalCap()).isEqualByComparingTo("10");
        assertThat(allocation.committedMonthly()).isEqualByComparingTo("20");
        assertThat(allocation.committedDaily()).isEqualByComparingTo("5");
        assertThat(allocation.committedWeekly()).isEqualByComparingTo("1");
        assertThat(allocation.committedCreditLimit()).isEqualByComparingTo("36");
    }

    /** Money a revoked or expired key held is not promised to anybody any more. */
    @Test
    void revokedAndExpiredKeysHoldNoMoney() {
        long account = account("dead-keys");
        key(account, "10", null, "ACTIVE", "hash-live", null, null);
        key(account, "100", null, "REVOKED", "hash-revoked", null, null);
        key(account, "1000", null, "ACTIVE", "hash-expired", null,
                Instant.now().minusSeconds(3600));

        OpenRouterAllocationQuery.Allocation allocation = allocationQuery.forAccount(account);

        assertThat(allocation.committedCreditLimit()).isEqualByComparingTo("10");
        assertThat(allocation.committedKeyCount()).isEqualTo(1);
    }

    /**
     * The two usage columns answer different questions. Remaining commitment is
     * limit minus the current window's usage, because that is the figure the
     * limit is enforced against; committed usage is the reset-aware running
     * total, because that is what the key has actually spent.
     */
    @Test
    void remainingUsesTheWindowUsageAndTheTotalUsesTheRunningSum() {
        long account = account("usage-axes");
        key(account, "10", "MONTHLY", "ACTIVE", "hash-window", "4", null);
        jdbcTemplate.update(
                "update llm_api_keys set openrouter_accounted_usage = 34 "
                        + "where openrouter_key_hash = 'hash-window'");

        OpenRouterAllocationQuery.Allocation allocation = allocationQuery.forAccount(account);

        assertThat(allocation.remainingCommitment()).isEqualByComparingTo("6");
        assertThat(allocation.committedUsage()).isEqualByComparingTo("34");
        assertThat(allocation.usageUnreportedKeyCount()).isZero();
    }

    /**
     * An overspent key cannot lend its overspend to the others: remaining is
     * floored per key, so one key past its limit does not read as headroom
     * somewhere else.
     */
    @Test
    void oneOverspentKeyDoesNotOffsetAnother() {
        long account = account("overspend");
        key(account, "10", null, "ACTIVE", "hash-over", "18", null);
        key(account, "10", null, "ACTIVE", "hash-under", "2", null);

        OpenRouterAllocationQuery.Allocation allocation = allocationQuery.forAccount(account);

        assertThat(allocation.remainingCommitment()).isEqualByComparingTo("8");
    }

    /**
     * Not provisioned and not reported are different facts, and the second one
     * is what makes remaining commitment an upper bound rather than a reading.
     */
    @Test
    void unreportedAndUnprovisionedKeysAreCountedSeparately() {
        long account = account("unreported");
        key(account, "10", null, "PENDING", null, null, null);
        key(account, "10", null, "ACTIVE", "hash-silent", null, null);
        key(account, "10", null, "ACTIVE", "hash-reporting", "3", null);

        OpenRouterAllocationQuery.Allocation allocation = allocationQuery.forAccount(account);

        assertThat(allocation.awaitingProvisionKeyCount()).isEqualTo(1);
        assertThat(allocation.usageUnreportedKeyCount()).isEqualTo(1);
        assertThat(allocation.remainingCommitment()).isEqualByComparingTo("27");
    }

    /**
     * An account nobody has drawn on has an allocation of zero, not a missing
     * one. The grouped query returns no row for it, and the caller must never
     * have to decide what that absence meant.
     */
    @Test
    void anAccountWithNoKeysAnswersZeroRatherThanNothing() {
        long empty = account("empty");
        long busy = account("busy");
        key(busy, "7", null, "ACTIVE", "hash-busy", null, null);

        Map<Long, OpenRouterAllocationQuery.Allocation> allocations =
                allocationQuery.forAccounts(List.of(empty, busy));

        assertThat(allocations).containsOnlyKeys(empty, busy);
        assertThat(allocations.get(empty).committedCreditLimit()).isEqualByComparingTo("0");
        assertThat(allocations.get(empty).committedKeyCount()).isZero();
        assertThat(allocations.get(busy).committedCreditLimit()).isEqualByComparingTo("7");
    }

    /**
     * Money is summed per account and never across them. Two accounts of the
     * same institution are two budgets, and a key drawing on one must not read
     * as headroom spent on the other.
     */
    @Test
    void aKeyBoundToAnotherAccountStaysOutOfThisOnesSum() {
        long here = account("here");
        long elsewhere = account("elsewhere");
        key(here, "10", null, "ACTIVE", "hash-here", null, null);
        key(elsewhere, "500", null, "ACTIVE", "hash-elsewhere", null, null);

        Map<Long, OpenRouterAllocationQuery.Allocation> allocations =
                allocationQuery.forAccounts(List.of(here, elsewhere));

        assertThat(allocations.get(here).committedCreditLimit()).isEqualByComparingTo("10");
        assertThat(allocations.get(elsewhere).committedCreditLimit()).isEqualByComparingTo("500");
    }

    /**
     * A key bound to no account at all draws on no registered budget, so it
     * joins no sum. Nothing forces such a key to exist any more, but a row that
     * predates the account model must not silently land in someone's total.
     */
    @Test
    void anUnboundKeyJoinsNoAccountsSum() {
        long account = account("unbound");
        key(account, "10", null, "ACTIVE", "hash-bound", null, null);
        long workspaceId = workspace();
        jdbcTemplate.update("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit,
                        openrouter_account_id, created_by)
                values (?, ?, ?, ?, 0, null, ?)
                """, workspaceId, orgId, request(workspaceId),
                "unbound-" + UUID.randomUUID(), adminId);

        assertThat(allocationQuery.forAccount(account).committedCreditLimit())
                .isEqualByComparingTo("10");
        assertThat(allocationQuery.forAccount(account).committedKeyCount()).isEqualTo(1);
    }

    /**
     * The record a grant leaves behind. An unobserved balance makes the verdict
     * null rather than false: not knowing is not the same as being within.
     */
    @Test
    void theGrantRecordProjectsTheDeltaAndLeavesAnUnknownBalanceUnjudged() {
        long account = account("record");
        key(account, "90", null, "ACTIVE", "hash-record", null, null);

        Map<String, Object> unobserved = allocationQuery.grantRecord(account, new BigDecimal("20"));
        assertThat((BigDecimal) unobserved.get("accountCommittedCreditLimit"))
                .isEqualByComparingTo("90");
        assertThat((BigDecimal) unobserved.get("accountProjectedRemainingCommitment"))
                .isEqualByComparingTo("110");
        assertThat(unobserved.get("accountBalance")).isNull();
        assertThat(unobserved.get("overAllocated")).isNull();

        observedBalance(account, "100");

        assertThat(allocationQuery.grantRecord(account, new BigDecimal("20")).get("overAllocated"))
                .isEqualTo(true);
        assertThat(allocationQuery.grantRecord(account, new BigDecimal("10")).get("overAllocated"))
                .isEqualTo(false);
    }

    /**
     * A key whose money axis is closed holds no money, so it is neither counted
     * nor waiting for anything. The binding is immutable, so such keys stay on
     * the account forever once the limit is taken to zero, and counting them
     * would report "thirty keys" on an account that has committed nothing.
     */
    @Test
    void aKeyWithNoMoneyIsNeitherCountedNorAwaitingProvisioning() {
        long account = account("closed-axis");
        key(account, "10", null, "ACTIVE", "hash-open", null, null);
        key(account, "0", null, "ACTIVE", null, null, null);

        OpenRouterAllocationQuery.Allocation allocation = allocationQuery.forAccount(account);

        assertThat(allocation.committedCreditLimit()).isEqualByComparingTo("10");
        assertThat(allocation.committedKeyCount()).isEqualTo(1);
        assertThat(allocation.awaitingProvisionKeyCount()).isZero();
    }

    /**
     * The balance is already net of what these keys have spent, so the verdict
     * measures what they can <em>still</em> draw against it. Measuring the whole
     * promise instead would charge the same dollars twice and call an account
     * over-allocated while it can cover every outstanding claim.
     */
    @Test
    void theVerdictComparesWhatIsStillDrawableAndNotTheWholePromise() {
        long account = account("no-double-count");
        key(account, "10", null, "ACTIVE", "hash-spent", "8", null);
        observedBalance(account, "92");

        Map<String, Object> record = allocationQuery.grantRecord(account, new BigDecimal("90"));

        assertThat((BigDecimal) record.get("accountCommittedCreditLimit"))
                .isEqualByComparingTo("10");
        assertThat((BigDecimal) record.get("accountRemainingCommitment"))
                .isEqualByComparingTo("2");
        assertThat((BigDecimal) record.get("accountProjectedRemainingCommitment"))
                .isEqualByComparingTo("92");
        // 2 + 90 == 92 exactly: equal is not over.
        assertThat(record.get("overAllocated")).isEqualTo(false);
        assertThat(allocationQuery.grantRecord(account, new BigDecimal("91")).get("overAllocated"))
                .isEqualTo(true);
    }

    /**
     * The classification of a failing poll travels with the figure. A balance
     * read three days ago and failing to refresh since is not the same evidence
     * as one read a minute ago, and the record has to let a later reader tell.
     */
    @Test
    void theRecordCarriesTheObservationTimeAndTheLastPollError() {
        long account = account("stale-balance");
        key(account, "10", null, "ACTIVE", "hash-stale", null, null);
        observedBalance(account, "5");
        jdbcTemplate.update("""
                update openrouter_accounts
                   set credits_error = 'THROTTLED'::openrouter_credential_error
                 where id = ?
                """, account);

        Map<String, Object> record = allocationQuery.grantRecord(account, BigDecimal.ZERO);

        assertThat(record.get("accountBalanceObservedAt")).isNotNull();
        assertThat(record.get("accountBalanceError")).isEqualTo("THROTTLED");
        assertThat(record.get("overAllocated")).isEqualTo(true);
    }

    private void observedBalance(long accountId, String balance) {
        jdbcTemplate.update("""
                update openrouter_accounts
                   set credits_total = ?::numeric, credits_usage = 0,
                       credits_observed_at = now(), credits_last_success_at = now()
                 where id = ?
                """, balance, accountId);
    }

    private long account(String name) {
        return accountRepository.saveAndFlush(
                new OpenRouterAccount(orgId, name + "-" + UUID.randomUUID(), null, null, adminId))
                .getId();
    }

    private void key(long accountId, String creditLimit, String reset, String status,
            String keyHash, String windowUsage, Instant expiresAt) {
        long workspaceId = workspace();
        jdbcTemplate.update("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, token_hash, credit_limit,
                        credit_limit_reset, status, expires_at, openrouter_account_id,
                        openrouter_key_hash, openrouter_key_enc,
                        openrouter_usage, openrouter_usage_at, created_by)
                values (?, ?, ?, ?, ?, ?::numeric, ?, ?::llm_api_key_status, ?, ?, ?, ?,
                        ?::numeric, ?, ?)
                """, workspaceId, orgId, request(workspaceId), "key-" + UUID.randomUUID(),
                "PENDING".equals(status) ? null : "token-" + UUID.randomUUID(),
                creditLimit, reset, status,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                accountId, keyHash, keyHash == null ? null : "ciphertext", windowUsage,
                windowUsage == null ? null : java.sql.Timestamp.from(Instant.now()), adminId);
    }

    private long workspace() {
        return jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name)
                values ('TEAM'::workspace_kind, ?) returning id
                """, Long.class, "allocation-" + UUID.randomUUID());
    }

    private long request(long workspaceId) {
        return jdbcTemplate.queryForObject("""
                insert into requests
                       (resource_type, workspace_id, org_id, requester_id, purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '시험', ?) returning id
                """, Long.class, workspaceId, orgId, adminId, "allocation-" + UUID.randomUUID());
    }
}
