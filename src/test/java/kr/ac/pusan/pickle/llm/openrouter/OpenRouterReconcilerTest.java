package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
 * The OpenRouter reconciliation: an orphan (theirs, unexplained) and a zombie
 * (ours over, theirs alive) land as drift findings; the zombie is disabled;
 * a healthy pairing is untouched; and a resolved mismatch auto-resolves on
 * the next cycle.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class OpenRouterReconcilerTest {

    @Autowired
    private OpenRouterReconciler reconciler;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private OpenRouterClient client;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from drift_findings where kind in "
                + "('OPENROUTER_ORPHAN'::drift_finding_kind, 'OPENROUTER_STALE'::drift_finding_kind)");
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_credit_usage_snapshots");
        jdbcTemplate.update("delete from llm_api_keys");
        when(client.configured()).thenReturn(true);
    }

    @Test
    void orphansAndZombiesLandAsFindingsAndTheZombieIsDisabled() {
        long healthy = insertKey("ACTIVE", "hash-live");
        insertKey("REVOKED", "hash-zombie");
        when(client.listKeys()).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("hash-live", "live", false,
                        new BigDecimal("5"), null, null),
                new OpenRouterClient.ManagedKey("hash-zombie", "zombie", false, null, null, null),
                new OpenRouterClient.ManagedKey("hash-orphan", "who-is-this", false, null, null, null)));

        reconciler.reconcile();

        assertThat(openFindings("OPENROUTER_ORPHAN")).containsExactly("hash-orphan");
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-zombie");
        verify(client).setDisabled("hash-zombie", true);
        verify(client, never()).setDisabled("hash-live", true);
        assertThat(healthy).isPositive();
    }

    @Test
    void aLiveKeyWhoseRemoteHalfVanishedIsReportedNotResolvedAway() {
        insertKey("ACTIVE", "hash-vanished");
        when(client.listKeys()).thenReturn(List.of());

        reconciler.reconcile();

        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-vanished");
    }

    @Test
    void aRepairedMismatchAutoResolvesOnTheNextCycle() {
        insertKey("REVOKED", "hash-z");
        when(client.listKeys()).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("hash-z", "z", false, null, null, null)));
        reconciler.reconcile();
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-z");

        // Now OpenRouter reports it disabled: the drift no longer holds.
        when(client.listKeys()).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("hash-z", "z", true, null, null, null)));
        reconciler.reconcile();

        assertThat(openFindings("OPENROUTER_STALE")).isEmpty();
    }

    @Test
    void aDivergedMoneyLimitIsReappliedAndReported() {
        // The ceiling is enforced there, so a limit that no longer matches
        // what we granted is a spend allowance nobody chose — push ours back
        // and say so.
        insertKey("ACTIVE", "hash-drifted");
        when(client.listKeys()).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-drifted", "d", false, new BigDecimal("99"), null, null)));

        reconciler.reconcile();

        verify(client).updateLimit("hash-drifted", new BigDecimal("5.00"), null);
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-drifted");
    }

    @Test
    void aMatchingLimitIsLeftAlone() {
        // Same amount written differently ($5 vs 5.00) is the same limit.
        insertKey("ACTIVE", "hash-ok");
        when(client.listKeys()).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-ok", "ok", false, new BigDecimal("5"), null, null)));

        reconciler.reconcile();

        verify(client, never()).updateLimit(anyString(), any(), any());
        assertThat(openFindings("OPENROUTER_STALE")).isEmpty();
    }

    @Test
    void aFindingSaysWhatHappenedNotWhatWasAttempted() {
        // The finding is the record of an intervention: if the remote call
        // failed, an operator reading it must not be told it succeeded.
        insertKey("REVOKED", "hash-stubborn");
        when(client.listKeys()).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-stubborn", "s", false, null, null, null)));
        org.mockito.Mockito.doThrow(new OpenRouterException(500, "nope"))
                .when(client).setDisabled("hash-stubborn", true);

        reconciler.reconcile();

        String summary = jdbcTemplate.queryForObject(
                "select summary from drift_findings where dedup_key = 'hash-stubborn'",
                String.class);
        assertThat(summary).contains("비활성화하지 못했습니다");
    }

    @Test
    void reportedSpendIsStoredOnTheKeyAndAppendedAsASnapshot() {
        // The money axis is enforced at OpenRouter, so their cumulative figure
        // is the one the console shows. One value is a gauge; the history is
        // what a depletion forecast reads a slope from.
        long id = insertKey("ACTIVE", "hash-spender");
        when(client.listKeys()).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-spender", "s", false, new BigDecimal("5"), null, new BigDecimal("1.75"))));

        reconciler.reconcile();

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
        when(client.listKeys()).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-silent", "s", false, new BigDecimal("5"), null, null)));

        reconciler.reconcile();

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
        when(client.listKeys()).thenReturn(List.of(new OpenRouterClient.ManagedKey(
                "hash-both", "b", false, new BigDecimal("99"), null, new BigDecimal("3.00"))));

        reconciler.reconcile();

        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-both");
        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_usage from llm_api_keys where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("3.00");
    }

    @Test
    void aFailedListingKeepsExistingFindings() {
        insertKey("REVOKED", "hash-kept");
        when(client.listKeys()).thenReturn(List.of(
                new OpenRouterClient.ManagedKey("hash-kept", "kept", false, null, null, null)));
        reconciler.reconcile();
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-kept");

        when(client.listKeys()).thenThrow(new OpenRouterException(503, "down"));
        reconciler.reconcile();

        // A failed read must not resolve anything for free.
        assertThat(openFindings("OPENROUTER_STALE")).containsExactly("hash-kept");
    }

    private List<String> openFindings(String kind) {
        return jdbcTemplate.queryForList("""
                select dedup_key from drift_findings
                 where kind = ?::drift_finding_kind and status = 'OPEN'
                 order by dedup_key
                """, String.class, kind);
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
                                          openrouter_key_hash, revoked_at, created_by)
                values (?, ?, ?, ?, ?, 'pickle-aa', ?::llm_api_key_status, 5, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + unique,
                String.format("%064x", requestId), status, openrouterHash, revokedAt, ownerId);
    }
}
