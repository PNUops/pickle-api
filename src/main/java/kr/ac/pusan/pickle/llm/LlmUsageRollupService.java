package kr.ac.pusan.pickle.llm;

import java.time.LocalDate;
import java.util.List;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
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
 * key, not from a timestamp: {@code id} is assigned at insert, so every row
 * this service has not yet seen is above it, whatever {@code requested_at}
 * says. <b>There is no seeded state row</b> — its absence reads as watermark
 * zero, and a refresh from zero is the backfill. A fresh database and one with
 * a year of events take the same path.
 *
 * <p>This job touches only the events (read), the rollup, and its own state
 * row. It never writes {@code llm_api_keys} or the generation counter, so it
 * stands outside the ingest path's lock order entirely and cannot join that
 * cycle. What it does need is protection from itself: two overlapping runs
 * would delete and re-insert the same day, so a session advisory lock makes
 * the second run a no-op rather than a lost race.
 */
@Service
public class LlmUsageRollupService {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRollupService.class);

    static final String JOB_ID = "llm-usage-rollup";

    /**
     * Arbitrary but fixed: advisory locks are namespaced only by this number,
     * so it must not collide with another advisory lock in this application.
     */
    private static final long ADVISORY_LOCK_KEY = 91_0001L;

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
     */
    private static final String REBUILD_DAY_SQL = """
            insert into llm_usage_daily (day, key_id, public_model_name, requests, succeeded,
                    rate_limited, failed, input_tokens, output_tokens, estimated_requests,
                    latency_ms_sum)
            select ?::date, e.key_id, e.public_model_name,
                   count(*),
                   count(*) filter (where e.status = 'OK'),
                   count(*) filter (where e.status = 'RATE_LIMITED'),
                   count(*) filter (where e.status is null
                                       or e.status not in ('OK', 'RATE_LIMITED')),
                   coalesce(sum(e.input_tokens), 0),
                   coalesce(sum(e.output_tokens), 0),
                   count(*) filter (where e.estimated),
                   coalesce(sum(e.latency_ms), 0)
              from llm_usage_events e
             where e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
               and e.requested_at < (?::date + 1)::timestamp at time zone 'Asia/Seoul'
             group by e.key_id, e.public_model_name
            """;

    private static final String ADVANCE_SQL = """
            insert into llm_usage_rollup_state (id, last_event_id, updated_at)
            values (true, ?, now())
            on conflict (id) do update
                    set last_event_id = excluded.last_event_id, updated_at = excluded.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final LlmUsageRetentionPolicy retentionPolicy;

    public LlmUsageRollupService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
            LlmUsageRetentionPolicy retentionPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.retentionPolicy = retentionPolicy;
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
        Boolean acquired = jdbcTemplate.queryForObject(
                "select pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            // A refresh is already running — very likely a backfill. Returning
            // is right: the running one will cover these events too.
            log.info("usage rollup refresh already in progress, skipping this run");
            return 0;
        }
        try {
            return rebuildAffectedDays();
        } finally {
            jdbcTemplate.queryForObject(
                    "select pg_advisory_unlock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        }
    }

    private int rebuildAffectedDays() {
        long watermark = currentWatermark();
        Long highest = jdbcTemplate.queryForObject(
                "select max(id) from llm_usage_events", Long.class);
        if (highest == null || highest <= watermark) {
            return 0;
        }
        List<LocalDate> days = jdbcTemplate.queryForList(
                AFFECTED_DAYS_SQL, LocalDate.class, watermark, highest);
        LocalDate frozenBefore = retentionPolicy.sweptBefore();
        int rebuilt = 0;
        for (LocalDate day : days) {
            if (frozenBefore != null && day.isBefore(frozenBefore)) {
                // The raw events of this day have been swept, so its rollup row
                // is now the only record of it. These events are the tail of a
                // re-send after a lost checkpoint: recomputing from what little
                // survived the sweep would overwrite a complete bucket with a
                // fragment. The retention sweep deletes them again on its next
                // run.
                log.info("usage rollup skipping {}: raw events for that day have been swept", day);
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

    /** The watermark, or zero when no state row exists yet (a fresh database). */
    long currentWatermark() {
        Long stored = jdbcTemplate.queryForObject(
                "select coalesce(max(last_event_id), 0) from llm_usage_rollup_state", Long.class);
        return stored == null ? 0L : stored;
    }
}
