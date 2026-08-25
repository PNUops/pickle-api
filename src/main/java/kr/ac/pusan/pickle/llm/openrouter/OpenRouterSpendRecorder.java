package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 * <p><b>Each key is its own auto-committed statement, in key order.</b> The
 * ingest path stamps {@code last_used_at} across many keys in one transaction
 * in whatever order its map yields, so a second writer holding several of these
 * row locks at once could deadlock against it. Single-row statements hold one
 * lock at a time and cannot; the ordering is the belt to that suspenders, in
 * case somebody later batches these. <b>This must not be wrapped in a
 * transaction.</b>
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
               set openrouter_usage = ?, openrouter_usage_at = ?
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
    public record Spend(long keyId, BigDecimal usage, BigDecimal creditLimit) {
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
                    jdbcTemplate.update(UPDATE_KEY_SQL, spend.usage(), at, spend.keyId());
                    jdbcTemplate.update(INSERT_SNAPSHOT_SQL, spend.keyId(), spend.usage(),
                            spend.creditLimit(), at);
                });
        jdbcTemplate.update(PRUNE_SQL,
                Timestamp.from(readAt.minus(java.time.Duration.ofDays(SNAPSHOT_RETENTION_DAYS))));
    }
}
