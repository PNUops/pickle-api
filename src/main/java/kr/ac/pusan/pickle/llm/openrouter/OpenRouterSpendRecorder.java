package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores what OpenRouter last said a key had spent, so the console can show a
 * money-axis figure without anyone opening the OpenRouter console.
 *
 * <p>Two writes per key: the current total on the key row (what a gauge reads)
 * and an appended snapshot (what a depletion forecast reads — one figure has no
 * slope). Both are stamped with the time the listing was read, because a
 * half-hourly job means the number on screen is always that much behind and a
 * screen that does not say so is lying quietly.
 *
 * <p>Legacy observations remain one auto-committed statement per key. An
 * account PAIR is different: its claim, reset-aware ledger and scoped drift
 * writes are one transaction, locking the account first and key rows in id
 * order. That ordering is load-bearing. The only other multi-key writers
 * ({@code LlmQuotaService} and usage {@code last_used_at}) also sort by id,
 * and the CREDIT-402 account trigger is written only after the usage
 * transaction commits. Reintroducing an unsorted key loop or an in-transaction
 * key-to-account trigger recreates a deadlock cycle.
 *
 * <p>Nothing here bumps the gateway generation: these columns never ride the
 * sync document, and bumping for them would hand the gateway a full document
 * every half hour for a number it has no use for.
 */
@Component
public class OpenRouterSpendRecorder {

    /**
     * How long spend snapshots are kept. Long enough for any forecast window
     * (the forecast reads days, not months) and short enough that a row per key
     * per half hour never becomes a table anybody has to think about.
     */
    private static final int SNAPSHOT_RETENTION_DAYS = 90;

    private static final String UPDATE_KEY_SQL = """
            update llm_api_keys
               set openrouter_usage = ?, openrouter_usage_at = ?,
                   openrouter_limit_remaining = ?
             where id = ?
            """;

    private static final String UPDATE_ACCOUNT_KEY_SQL = """
            update llm_api_keys
               set openrouter_accounted_usage = case
                       when not ? then 0
                       else openrouter_accounted_usage
                            + case
                                when openrouter_usage is null then ?
                                when ? >= openrouter_usage then ? - openrouter_usage
                                else ?
                              end
                     end,
                   openrouter_usage = ?, openrouter_usage_at = ?,
                   openrouter_limit_remaining = ?
             where id = ?
            """;

    private static final String INSERT_SNAPSHOT_SQL = """
            insert into llm_credit_usage_snapshots (key_id, usage_amount, credit_limit, captured_at)
            values (?, ?, ?, ?)
            """;

    private static final String PRUNE_SQL = """
            delete from llm_credit_usage_snapshots
             where captured_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public OpenRouterSpendRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One key's reported spend at the moment the listing was read. */
    public record Spend(long keyId, BigDecimal usage, BigDecimal creditLimit,
            @org.jspecify.annotations.Nullable BigDecimal limitRemaining) {

        public Spend(long keyId, BigDecimal usage, BigDecimal creditLimit) {
            this(keyId, usage, creditLimit, null);
        }
    }

    /**
     * Records every reported spend and prunes old snapshots. Called once per
     * reconciliation, after its verdicts, so a failure here cannot cost a
     * drift finding.
     */
    public void record(List<Spend> spends, Instant readAt) {
        Timestamp at = Timestamp.from(readAt);
        spends.stream()
                .sorted(Comparator.comparingLong(Spend::keyId))
                .forEach(spend -> {
                    jdbcTemplate.update(UPDATE_KEY_SQL, spend.usage(), at,
                            spend.limitRemaining(), spend.keyId());
                    jdbcTemplate.update(INSERT_SNAPSHOT_SQL, spend.keyId(), spend.usage(),
                            spend.creditLimit(), at);
                });
        prune(readAt);
    }

    /**
     * Account-bound observation. Each key row atomically advances a reset-aware
     * cumulative amount, so a process crash between keys cannot lose already
     * accounted deltas. The first pair establishes the account baseline and
     * therefore does not count pre-baseline key usage.
     */
    @Transactional
    public AccountRecordResult recordAccount(List<Spend> spends, Instant readAt,
            boolean baselineExists,
            OpenRouterPollRepository.Claim claim, Runnable protectedWrites) {
        Boolean active = jdbcTemplate.query("""
                select true
                  from openrouter_accounts a
                  join openrouter_account_credentials c
                    on c.id = a.poll_claim_credential_id and c.account_id = a.id
                 where a.id = ? and a.poll_claim_token = ?
                   and a.poll_claim_credential_id = ?
                   and a.poll_claim_until > ?
                   and c.status = 'ACTIVE'::openrouter_credential_status
                 for update of a
                """, rs -> rs.next() ? Boolean.TRUE : null,
                claim.accountId(), claim.token(), claim.credentialId(),
                Timestamp.from(readAt));
        if (!Boolean.TRUE.equals(active)) {
            return new AccountRecordResult(false, false);
        }
        Timestamp at = Timestamp.from(readAt);
        boolean[] resetBoundary = {false};
        spends.stream()
                .sorted(Comparator.comparingLong(Spend::keyId))
                .forEach(spend -> {
                    BigDecimal previous = jdbcTemplate.queryForObject(
                            "select openrouter_usage from llm_api_keys where id = ? for update",
                            BigDecimal.class, spend.keyId());
                    if (previous != null && spend.usage().compareTo(previous) < 0) {
                        resetBoundary[0] = true;
                    }
                    int changed = jdbcTemplate.update(UPDATE_ACCOUNT_KEY_SQL,
                            baselineExists, spend.usage(), spend.usage(), spend.usage(),
                            spend.usage(), spend.usage(), at, spend.limitRemaining(),
                            spend.keyId());
                    if (changed != 1) {
                        throw new OpenRouterException(0,
                                "managed key disappeared while recording usage");
                    }
                    jdbcTemplate.update(INSERT_SNAPSHOT_SQL, spend.keyId(), spend.usage(),
                            spend.creditLimit(), at);
                });
        prune(readAt);
        protectedWrites.run();
        return new AccountRecordResult(true, resetBoundary[0]);
    }

    private void prune(Instant readAt) {
        jdbcTemplate.update(PRUNE_SQL,
                Timestamp.from(readAt.minus(java.time.Duration.ofDays(SNAPSHOT_RETENTION_DAYS))));
    }

    public record AccountRecordResult(boolean persisted, boolean resetBoundary) {
    }
}
