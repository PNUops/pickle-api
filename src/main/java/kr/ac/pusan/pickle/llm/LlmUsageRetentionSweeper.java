package kr.ac.pusan.pickle.llm;

import java.time.LocalDate;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Freezes complete KST days before deleting their raw events in bounded
 * batches (04:40 KST). Rollup and retention share a session lock, while raw
 * ingestion waits only for the short freeze transaction's commit fence.
 *
 * <p>Nothing runs until retention is configured. Once a day is frozen, a late
 * re-sent fragment cannot repaint its complete daily aggregate; its raw row
 * is removed by a later retention pass. The raw-only diagnostics stop reaching
 * back past the configured cutoff as rows are physically removed.</p>
 */
@Component
public class LlmUsageRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRetentionSweeper.class);

    static final String JOB_ID = "llm-usage-retention-sweeper";
    private static final int BATCH_SIZE = 1000;

    private static final String DELETE_SQL = """
            delete from llm_usage_events
             where id in (select id from llm_usage_events
                           where requested_at < ?::date::timestamp at time zone 'Asia/Seoul'
                             and requested_at < ?::date::timestamp at time zone 'Asia/Seoul'
                           order by id limit ?)
            """;

    /** The first expired KST day with an event not yet in the rollup. */
    private static final String FIRST_UNROLLED_DAY_SQL = """
            select min((requested_at at time zone 'Asia/Seoul')::date)
              from llm_usage_events
             where requested_at < ?::date::timestamp at time zone 'Asia/Seoul'
               and id > ?
            """;

    /** Only forward: a crash may leave raw rows inside the frozen prefix. */
    private static final String MARK_FROZEN_SQL = """
            insert into llm_usage_rollup_state (id, swept_before, updated_at)
            values (true, ?, now())
            on conflict (id) do update
                    set swept_before = greatest(
                            coalesce(llm_usage_rollup_state.swept_before, excluded.swept_before),
                            excluded.swept_before),
                        updated_at = excluded.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LlmUsageRetentionPolicy retentionPolicy;
    private final LlmUsageRollupLock rollupLock;
    private final LlmUsageCommitFence commitFence;
    private final TransactionTemplate boundaryTransaction;

    public LlmUsageRetentionSweeper(JdbcTemplate jdbcTemplate,
            LlmUsageRetentionPolicy retentionPolicy, LlmUsageRollupLock rollupLock,
            LlmUsageCommitFence commitFence, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionPolicy = retentionPolicy;
        this.rollupLock = rollupLock;
        this.commitFence = commitFence;
        this.boundaryTransaction = new TransactionTemplate(transactionManager);
        this.boundaryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "40 4 * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        LocalDate cutoff = retentionPolicy.cutoff();
        if (cutoff == null) {
            return;
        }
        rollupLock.run(() -> deleteFrozenPrefix(cutoff)).orElseThrow(() -> {
            log.warn("llm usage retention could not acquire the shared rollup lock");
            return new IllegalStateException("llm usage retention must retry after lock contention");
        });
    }

    private int deleteFrozenPrefix(LocalDate cutoff) {
        LocalDate frozenBefore = boundaryTransaction.execute(status -> {
            // Hold F only for the snapshot and mark. Ingest continues while
            // bounded deletions run under the longer-lived R session lock.
            commitFence.lockInTransaction();
            long watermark = jdbcTemplate.queryForObject(
                    "select coalesce(max(last_event_id), 0) from llm_usage_rollup_state",
                    Long.class);
            LocalDate firstUnrolled = jdbcTemplate.queryForObject(
                    FIRST_UNROLLED_DAY_SQL, LocalDate.class, cutoff, watermark);
            LocalDate existing = jdbcTemplate.queryForObject(
                    "select max(swept_before) from llm_usage_rollup_state", LocalDate.class);
            LocalDate eligible = firstUnrolled == null ? cutoff : firstUnrolled;
            LocalDate mark = existing == null || existing.isBefore(eligible) ? eligible : existing;
            jdbcTemplate.update(MARK_FROZEN_SQL, mark);
            return mark;
        });
        if (frozenBefore == null) {
            throw new IllegalStateException("llm usage retention could not freeze its date prefix");
        }
        int deleted = 0;
        int affected;
        do {
            // Keep both predicates: an operator may lengthen retention after
            // a prior freeze, so frozen raw in the new keep-window survives.
            affected = jdbcTemplate.update(DELETE_SQL, cutoff, frozenBefore, BATCH_SIZE);
            deleted += affected;
        } while (affected == BATCH_SIZE);
        if (deleted > 0) {
            log.info("llm usage retention deleted {} event(s) before frozen date {}",
                    deleted, frozenBefore);
        }
        return deleted;
    }
}
