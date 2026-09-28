package kr.ac.pusan.pickle.llm;

import java.time.LocalDate;
import java.util.List;
import org.jobrunr.jobs.annotations.Job;
import org.jspecify.annotations.Nullable;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code llm_usage_daily} in step with the raw usage events.
 *
 * <p><b>Rebuilds, never accumulates.</b> Usage arrives from the gateway in
 * batches whose order is not time order — a request that starts before UTC
 * midnight and finishes after it is appended to the earlier day's spool file
 * after that day has already shipped — so a day the rollup has written can
 * still gain events. Adding to a running total would make the result depend on
 * arrival order; this service instead recomputes every affected day from the
 * events themselves, which is idempotent no matter how often it runs or where
 * it is interrupted.
 *
 * <p>Which days are affected comes from a watermark over the events' primary
 * key, not from a timestamp. Ingest and the rollup snapshot share a short
 * transaction lock, so an uncommitted lower ID cannot be left behind when
 * the watermark advances, whatever {@code requested_at} says. <b>There is
 * no seeded state row</b> — its absence reads as watermark
 * zero, and a refresh from zero is the backfill. A fresh database and one with
 * a year of events take the same path.
 *
 * <p>This job touches only the events (read), the rollup, and its own state
 * row. It never writes {@code llm_api_keys} or the generation counter. It
 * acquires the ingest commit fence only for its short snapshot transaction,
 * after the shared rollup/retention session lock; ingest acquires only the
 * commit fence. Two overlapping runs would delete and re-insert the same day,
 * so the session lock makes the second run a no-op rather than a lost race.
 */
@Service
public class LlmUsageRollupService {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRollupService.class);

    static final String JOB_ID = "llm-usage-rollup";

    /**
     * The KST days carrying at least one event the rollup has not seen. Bounded
     * above by the snapshot of {@code max(id)} taken with it, so a row inserted
     * while the refresh runs belongs to the next run rather than being counted
     * as done by this one.
     */
    private static final String AFFECTED_DAYS_SQL = """
            select distinct (e.requested_at at time zone 'Asia/Seoul')::date as day
              from llm_usage_events e
             where e.id > ? and e.id <= ?
             order by day
            """;

    private static final String DELETE_DAY_SQL = "delete from llm_usage_daily where day = ?";

    /**
     * One day's buckets, rebuilt from every event of that day — not only the
     * ones above the watermark, because a bucket is a total and a partial
     * recount is a wrong total.
     *
     * <p>The status vocabulary matches the per-key series exactly: OK and
     * RATE_LIMITED are named, and everything else is a failure. A status this
     * code has never heard of is still a request that happened, so it lands in
     * {@code failed} rather than vanishing from the counts.
     *
     * <p>{@code endpoint} is part of the bucket key rather than a set of
     * counter columns, which is the opposite of what the budget axis did. The
     * axis is closed at two values so three counters cover it forever; a route
     * name is the gateway's open vocabulary, so counters would need a
     * migration per route and per-route sums would need one column per route
     * and measure. Grouping costs almost no rows, because a model is reached
     * from essentially one route.
     *
     * <p>{@code served_model_name} is counted rather than grouped, and the
     * reason is the same test read the other way: its cardinality is the
     * vendor's catalogue rather than the names we issue, and a router can
     * answer with any of thousands. The mismatch count says whether to look;
     * the names themselves are read from raw events when someone does.
     *
     * <p><b>Its presence is the mismatch.</b> This column used to be compared
     * against {@code public_model_name}, which was wrong in a way that showed
     * up as a fallback on every ordinary self-hosted request: the two names
     * live in different namespaces, and a public name differing from the
     * upstream one is what a public name is for. The gateway holds the only
     * pair worth comparing, so it decides there and writes the column only
     * when the answer differed from what it asked for.
     */
    private static final String REBUILD_DAY_SQL = """
            insert into llm_usage_daily (day, key_id, public_model_name, endpoint, requests,
                    succeeded, rate_limited, failed, input_tokens, output_tokens,
                    estimated_requests, latency_ms_sum, token_axis_requests,
                    credit_axis_requests, unknown_axis_requests, estimated_tokens,
                    cost_usd, priced_requests, priced_input_tokens, priced_output_tokens,
                    priced_latency_ms_sum, priced_failed, image_count, cached_input_tokens,
                    reasoning_tokens, served_mismatch_requests, streamed_requests)
            select ?::date, e.key_id, e.public_model_name, e.endpoint,
                   count(*),
                   count(*) filter (where e.status = 'OK'),
                   count(*) filter (where e.status = 'RATE_LIMITED'),
                   count(*) filter (where e.status is null
                                       or e.status not in ('OK', 'RATE_LIMITED')),
                   coalesce(sum(e.input_tokens), 0),
                   coalesce(sum(e.output_tokens), 0),
                   count(*) filter (where e.estimated),
                   coalesce(sum(e.latency_ms), 0),
                   count(*) filter (where e.budget_axis = 'TOKEN'),
                   count(*) filter (where e.budget_axis = 'CREDIT'),
                   count(*) filter (where e.budget_axis is null),
                   coalesce(sum(e.input_tokens::bigint + e.output_tokens::bigint)
                       filter (where e.estimated), 0),
                   coalesce(sum(e.cost_usd), 0),
                   count(*) filter (where e.cost_usd is not null),
                   coalesce(sum(e.input_tokens) filter (where e.cost_usd is not null), 0),
                   coalesce(sum(e.output_tokens) filter (where e.cost_usd is not null), 0),
                   coalesce(sum(e.latency_ms) filter (where e.cost_usd is not null), 0),
                   count(*) filter (where e.cost_usd is not null
                                      and (e.status is null
                                           or e.status not in ('OK', 'RATE_LIMITED'))),
                   coalesce(sum(e.image_count), 0),
                   coalesce(sum(e.cached_input_tokens::bigint), 0),
                   coalesce(sum(e.reasoning_tokens::bigint), 0),
                   count(*) filter (where e.served_model_name is not null),
                   count(*) filter (where e.streamed)
              from llm_usage_events e
             where e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
               and e.requested_at < (?::date + 1)::timestamp at time zone 'Asia/Seoul'
             group by e.key_id, e.public_model_name, e.endpoint
            """;

    private static final String ADVANCE_SQL = """
            insert into llm_usage_rollup_state
                (id, last_event_id, updated_at, last_success_at)
            values (true, ?, now(), now())
            on conflict (id) do update
                    set last_event_id = excluded.last_event_id,
                        updated_at = excluded.updated_at,
                        last_success_at = excluded.last_success_at
            """;

    private static final String MARK_NOOP_SUCCESS_SQL = """
            insert into llm_usage_rollup_state (id, last_event_id, last_success_at)
            values (true, 0, now())
            on conflict (id) do update set last_success_at = excluded.last_success_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate snapshotTransaction;
    private final LlmUsageRollupLock rollupLock;
    private final LlmUsageCommitFence commitFence;

    public LlmUsageRollupService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
            LlmUsageRollupLock rollupLock, PlatformTransactionManager transactionManager,
            LlmUsageCommitFence commitFence) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.rollupLock = rollupLock;
        this.commitFence = commitFence;
    }

    /** One refresh. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "*/5 * * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        refresh();
    }

    /**
     * @return how many day buckets were rebuilt; zero when nothing new arrived.
     */
    public int refresh() {
        return rollupLock.run(() -> rebuildAffectedDays(captureSnapshot()))
                .orElseGet(() -> {
                    // Another rollup or retention pass owns this database.
                    log.info("usage rollup refresh already in progress, skipping this run");
                    return 0;
                });
    }

    private RollupSnapshot captureSnapshot() {
        RollupSnapshot snapshot = snapshotTransaction.execute(status -> {
            // The lock is held only until this short transaction commits.
            // Every raw writer that obtained an ID before this point has
            // committed, and new writers cannot obtain an ID until it ends.
            commitFence.lockInTransaction();
            long watermark = currentWatermark();
            Long highest = jdbcTemplate.queryForObject(
                    "select max(id) from llm_usage_events", Long.class);
            List<LocalDate> days = highest == null || highest <= watermark
                    ? List.of() : jdbcTemplate.queryForList(
                            AFFECTED_DAYS_SQL, LocalDate.class, watermark, highest);
            return new RollupSnapshot(watermark, highest, days);
        });
        if (snapshot == null) {
            throw new IllegalStateException("LLM usage rollup could not capture its ID snapshot");
        }
        return snapshot;
    }

    private int rebuildAffectedDays(RollupSnapshot snapshot) {
        long watermark = snapshot.watermark();
        Long highest = snapshot.highest();
        if (highest == null || highest <= watermark) {
            jdbcTemplate.update(MARK_NOOP_SUCCESS_SQL);
            return 0;
        }
        List<LocalDate> days = snapshot.days();
        LocalDate frozenBefore = sweptBefore();
        int rebuilt = 0;
        for (LocalDate day : days) {
            if (frozenBefore != null && day.isBefore(frozenBefore)) {
                // This day is frozen even if physical raw deletion is delayed.
                // Rebuilding from a partial raw set would overwrite a complete
                // bucket with a fragment. Retention deletes a late raw resend
                // during a later pass.
                log.info("usage rollup skipping frozen day {}", day);
                continue;
            }
            // One transaction per day: the backfill may touch every day the
            // platform has ever served, and a single transaction over all of
            // them would hold locks for its whole duration for no benefit — a
            // crash halfway simply leaves the watermark where it was, and the
            // next run redoes the same days to the same result.
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(DELETE_DAY_SQL, day);
                jdbcTemplate.update(REBUILD_DAY_SQL, day, day, day);
            });
            rebuilt++;
        }
        // Advanced only once every day is written, so an interrupted run repeats
        // work rather than skipping it.
        jdbcTemplate.update(ADVANCE_SQL, highest);
        if (rebuilt > 0) {
            log.info("usage rollup rebuilt {} day bucket(s) up to event id {}", rebuilt, highest);
        }
        return rebuilt;
    }

    private record RollupSnapshot(long watermark, @Nullable Long highest, List<LocalDate> days) {
    }

    /** The watermark, or zero when no state row exists yet (a fresh database). */
    long currentWatermark() {
        Long stored = jdbcTemplate.queryForObject(
                "select coalesce(max(last_event_id), 0) from llm_usage_rollup_state", Long.class);
        return stored == null ? 0L : stored;
    }

    /**
     * The exclusive KST-day freeze boundary, or null before the first sweep.
     * Physical deletion may lag this mark after a crash between bounded
     * batches. A frozen day's rollup must never be rebuilt from those raw
     * leftovers or from a later re-sent fragment.
     */
    @Nullable
    LocalDate sweptBefore() {
        return jdbcTemplate.queryForObject(
                "select max(swept_before) from llm_usage_rollup_state", LocalDate.class);
    }
}
