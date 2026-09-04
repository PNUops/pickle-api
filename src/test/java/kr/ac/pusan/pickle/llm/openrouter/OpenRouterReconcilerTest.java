package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The OpenRouter reconciliation of one institution account: an orphan
 * (theirs, unexplained) and a zombie (ours over, theirs alive) land as drift
 * findings; the zombie is disabled; a healthy pairing is untouched; and a
 * resolved mismatch auto-resolves on the next cycle.
 *
 * <p>These drive {@code reconcileAccount}, which is the entry point the poll
 * dispatcher's worker actually calls. Driving anything else here would leave
 * the production path untested while the assertions still passed.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class OpenRouterReconcilerTest {

    /** The management credential this account's scope decrypts to. */
    private static final String ACCOUNT_KEY = "reconciler-account-management-key";

    @Autowired
    private OpenRouterReconciler reconciler;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OpenRouterManagementCredentialCipher cipher;
    @Autowired
    private OpenRouterPollRepository polls;
    @Autowired
    private OpenRouterCredentialResolver credentialResolver;
    @MockitoBean
    private OpenRouterClient client;

    private long accountId;
    private UUID accountPublicId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from drift_findings where kind in "
                + "('OPENROUTER_ORPHAN'::drift_finding_kind, 'OPENROUTER_STALE'::drift_finding_kind)");
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_credit_usage_snapshots");
        jdbcTemplate.update("delete from llm_request_bodies");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("delete from openrouter_credit_snapshots");
        jdbcTemplate.update("delete from openrouter_account_credentials");
        jdbcTemplate.update("delete from openrouter_accounts");
        accountId = insertAccount();
    }

    /**
     * The one scope every case here runs in. The account has no vendor
     * workspace, so its listing is unscoped — which is what the stubs below
     * pass as the second argument.
     */
    private long insertAccount() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        long id = jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, ?, ?)
                returning id
                """, Long.class, orgId, "대사 시험 사업 " + UUID.randomUUID(), ownerId);
        accountPublicId = jdbcTemplate.queryForObject(
                "select public_id from openrouter_accounts where id = ?", UUID.class, id);
        jdbcTemplate.update("""
                insert into openrouter_account_credentials
                       (account_id, status, credential_enc, created_by,
                        activated_at, verified_at)
                values (?, 'ACTIVE'::openrouter_credential_status, ?, ?, now(), now())
                """, id, cipher.encrypt(accountPublicId, ACCOUNT_KEY), ownerId);
        return id;
    }

    /**
     * One reconciliation cycle over this test's account, the way the poll
     * worker runs it. The claim is released afterwards so a test can run a
     * second cycle; in production the worker's own success or abandon call
     * does that.
     */
    private void reconcile() {
        OpenRouterManagementAccess access =
                credentialResolver.forAccount(accountPublicId).orElseThrow();
        OpenRouterPollRepository.Claim claim = polls.claim(accountId, Instant.now());
        assertThat(claim).as("the account should be claimable for a poll").isNotNull();
        OpenRouterReconciler.ScopeObservation observation;
        try {
            observation = reconciler.reconcileAccount(access, claim, Instant.now(),
                    polls.baselineExists(claim), Clock.systemUTC());
        } finally {
            polls.abandon(claim);
        }
        // Every database assertion in this file sits behind the claim gate:
        // when `recordAccount` cannot confirm the claim it writes nothing, runs
        // no findings, and says so only through this flag. A test that expects
        // an absence would then pass without the cycle having happened, so the
        // flag is checked here rather than left to each case to remember.
        assertThat(observation.persisted())
                .as("the cycle must have committed, or an absence proves nothing")
                .isTrue();
    }

    @Test
    void orphansAndZombiesLandAsFindingsAndTheZombieIsDisabled() {
        long healthy = insertKey("ACTIVE", "hash-live");
        insertKey("REVOKED", "hash-zombie");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("hash-live", "live", false,
                        new BigDecimal("5"), null, true, null),
                new OpenRouterClient.ManagedKey("hash-zombie", "zombie", false, null, null, true, null),
                new OpenRouterClient.ManagedKey("hash-orphan", "who-is-this", false, null, null, true, null)));

        reconcile();

        assertThat(openFindings("OPENROUTER_ORPHAN")).containsExactly("hash-orphan");
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-zombie");
        verify(client).setDisabled(ACCOUNT_KEY, null,
                "hash-zombie", true);
        verify(client, never()).setDisabled(ACCOUNT_KEY, null,
                "hash-live", true);
        assertThat(healthy).isPositive();
    }

    @Test
    void aLiveKeyWhoseRemoteHalfVanishedIsReportedNotResolvedAway() {
        insertKey("ACTIVE", "hash-vanished");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of());

        reconcile();

        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-vanished");
    }

    @Test
    void suspendedAndResumedKeysRepairRemoteDisabledState() {
        insertKey("SUSPENDED", "hash-suspended");
        insertKey("ACTIVE", "hash-resumed");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("hash-suspended", "s", false,
                        new BigDecimal("5"), null, true, null),
                new OpenRouterClient.ManagedKey("hash-resumed", "r", true,
                        new BigDecimal("5"), null, true, null)));

        reconcile();

        verify(client).setDisabled(ACCOUNT_KEY, null,
                "hash-suspended", true);
        verify(client).setDisabled(ACCOUNT_KEY, null,
                "hash-resumed", false);
        assertThat(openFindings("OPENROUTER_STALE"))
                .containsExactly("hash-resumed", "hash-suspended");
    }

    @Test
    void combinedLimitAndSuspensionDriftRepairsBothInOneCycle() {
        // Both post-commit writes were lost: OpenRouter still has the former
        // ceiling and still allows a key that pickle has suspended. One
        // reconcile pass must close both windows, not defer the status half
        // until the next 30-minute run.
        insertKey("SUSPENDED", "hash-combined");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-combined", "combined", false,
                new BigDecimal("99"), null, true, null)));

        reconcile();

        verify(client).updateLimit(ACCOUNT_KEY, null,
                "hash-combined", new BigDecimal("5.00"), null);
        verify(client).setDisabled(ACCOUNT_KEY, null,
                "hash-combined", true);
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-combined");
        assertThat(findingMessage("hash-combined"))
                .contains("금액 한도를 다시 적용했습니다")
                .contains("정지된 키의 OpenRouter 키를 비활성화했습니다");
    }

    @Test
    void aRepairedMismatchAutoResolvesOnTheNextCycle() {
        insertKey("REVOKED", "hash-z");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("hash-z", "z", false, null, null, true, null)));
        reconcile();
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-z");

        // Now OpenRouter reports it disabled: the drift no longer holds.
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("hash-z", "z", true, null, null, true, null)));
        reconcile();

        assertThat(openFindings("OPENROUTER_STALE")).isEmpty();
    }

    @Test
    void aDivergedMoneyLimitIsReappliedAndReported() {
        // The ceiling is enforced there, so a limit that no longer matches
        // what we granted is a spend allowance nobody chose — push ours back
        // and say so.
        insertKey("ACTIVE", "hash-drifted");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-drifted", "d", false, new BigDecimal("99"), null, true, null)));

        reconcile();

        verify(client).updateLimit(ACCOUNT_KEY, null,
                "hash-drifted", new BigDecimal("5.00"), null);
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-drifted");
    }

    @Test
    void aMatchingLimitIsLeftAlone() {
        // Same amount written differently ($5 vs 5.00) is the same limit.
        insertKey("ACTIVE", "hash-ok");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-ok", "ok", false, new BigDecimal("5"), null, true, null)));

        reconcile();

        verify(client, never()).updateLimit(anyString(), any(), anyString(), any(), any());
        assertThat(openFindings("OPENROUTER_STALE")).isEmpty();
    }

    @Test
    void aLimitThatDoesNotCountByokSpendIsRepairedEvenWhenTheAmountMatches() {
        // The dangerous shape: the ceiling reads correct on both sides, and
        // enforces nothing the moment a provider key is attached to the
        // account, because OpenRouter defaults include_byok_in_limit to false
        // and BYOK inference is then simply not counted. A wrong amount is
        // visible; this is not.
        insertKey("ACTIVE", "hash-uncounted");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-uncounted", "u", false, new BigDecimal("5"), null, false, null)));

        reconcile();

        verify(client).updateLimit(ACCOUNT_KEY, null,
                "hash-uncounted", new BigDecimal("5.00"), null);
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-uncounted");
        // The operator has to be able to act on this. Both amounts read 5, so
        // a finding that says the amount differs reads as the reconciler
        // malfunctioning and gets ignored.
        assertThat(findingMessage("hash-uncounted")).contains("BYOK");
        assertThat(findingMessage("hash-uncounted")).doesNotContain("금액이 부여값과 다름");
    }

    @Test
    void aFindingSaysWhatHappenedNotWhatWasAttempted() {
        // The finding is the record of an intervention: if the remote call
        // failed, an operator reading it must not be told it succeeded.
        insertKey("REVOKED", "hash-stubborn");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-stubborn", "s", false, null, null, true, null)));
        org.mockito.Mockito.doThrow(new OpenRouterException(500, "nope"))
                .when(client).setDisabled(ACCOUNT_KEY, null,
                        "hash-stubborn", true);

        reconcile();

        String summary = jdbcTemplate.queryForObject(
                "select summary from drift_findings where dedup_key like '%:key:hash-stubborn'",
                String.class);
        assertThat(summary).contains("비활성화하지 못했습니다");
    }

    @Test
    void reportedSpendIsStoredOnTheKeyAndAppendedAsASnapshot() {
        // The money axis is enforced at OpenRouter, so their cumulative figure
        // is the one the console shows. One value is a gauge; the history is
        // what a depletion forecast reads a slope from.
        long id = insertKey("ACTIVE", "hash-spender");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-spender", "s", false, new BigDecimal("5"), null, true, new BigDecimal("1.75"))));

        reconcile();

        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_usage from llm_api_keys where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("1.75");
        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_usage_at is not null from llm_api_keys where id = ?",
                Boolean.class, id)).isTrue();
        assertThat(jdbcTemplate.queryForList(
                "select usage_amount from llm_credit_usage_snapshots where key_id = ?",
                BigDecimal.class, id)).hasSize(1);
    }

    @Test
    void aKeyWhoseSpendIsNotReportedIsLeftUnknownRatherThanZeroed() {
        // Absent is not zero: writing zero would show a student a spend figure
        // nobody measured, and a forecast would read a slope from it.
        long id = insertKey("ACTIVE", "hash-silent");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-silent", "s", false, new BigDecimal("5"), null, true, null)));

        reconcile();

        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_usage from llm_api_keys where id = ?", BigDecimal.class, id))
                .isNull();
    }

    @Test
    void spendIsRecordedEvenWhenTheKeyAlsoDrifted() {
        // A key whose limit drifted has still spent what it spent, and that
        // branch returns early — so the spend must be collected before the
        // verdicts, not inside the healthy one.
        long id = insertKey("ACTIVE", "hash-both");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-both", "b", false, new BigDecimal("99"), null, true, new BigDecimal("3.00"))));

        reconcile();

        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-both");
        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_usage from llm_api_keys where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("3.00");
    }

    @Test
    void aFailedListingKeepsExistingFindings() {
        insertKey("REVOKED", "hash-kept");
        when(client.listKeys(ACCOUNT_KEY, null)).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("hash-kept", "kept", false, null, null, true, null)));
        reconcile();
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-kept");

        when(client.listKeys(ACCOUNT_KEY, null))
                .thenThrow(new OpenRouterException(503, "down"));
        // The failure travels out to the poll worker, which records it against
        // the account's own failure axis. Swallowing it here would let the
        // worker treat a vendor outage as a completed observation.
        assertThatThrownBy(this::reconcile).isInstanceOf(OpenRouterException.class);

        // A failed read must not resolve anything for free.
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-kept");
    }

    private String findingMessage(String dedupKey) {
        return jdbcTemplate.queryForObject(
                "select summary from drift_findings where dedup_key like ? and status = 'OPEN'",
                String.class, "%:key:" + dedupKey);
    }

    private List<String> openFindings(String kind) {
        return jdbcTemplate.queryForList("""
                select dedup_key from drift_findings
                 where kind = ?::drift_finding_kind and status = 'OPEN'
                 order by dedup_key
                """, String.class, kind).stream()
                .map(key -> key.substring(key.lastIndexOf(":key:") + 5)).toList();
    }

    private long insertKey(String status, String openrouterHash) {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "대사 시험 " + unique);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "대사 시험", "rc-" + unique);
        java.sql.Timestamp revokedAt = "REVOKED".equals(status)
                ? java.sql.Timestamp.from(Instant.now()) : null;
        return jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, credit_limit,
                                          openrouter_key_hash, openrouter_key_enc,
                                          openrouter_account_id, revoked_at, created_by)
                values (?, ?, ?, ?, ?, 'pickle-aa', ?::llm_api_key_status, 5, ?,
                        'test-runtime-ciphertext', ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + unique,
                String.format("%064x", requestId), status, openrouterHash, accountId,
                revokedAt, ownerId);
    }
}
