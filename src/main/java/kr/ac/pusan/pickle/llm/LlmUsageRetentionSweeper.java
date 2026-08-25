package kr.ac.pusan.pickle.llm;

import java.time.LocalDate;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes raw usage events past the configured retention (04:40 KST), in
 * bounded batches so a large backlog never holds a long lock.
 *
 * <p>Does nothing until an operator configures a retention — see
 * {@link LlmUsageRetentionPolicy}. Two conditions guard every delete:
 *
 * <ul>
 *   <li>the event's day is past the cutoff, and</li>
 *   <li>the event is at or below the rollup watermark, so its counts already
 *       live in the daily rollup. Without this an event could be deleted
 *       between arriving and being aggregated, and it would then exist in no
 *       record at all.</li>
 * </ul>
 *
 * <p>What this costs, stated plainly: after a day is swept, its aggregate can
 * no longer be recomputed from raw rows, and the statistics that read raw
 * events — latency percentiles, the hour-of-week distribution, per-upstream
 * diagnostics — stop reaching back past the cutoff.
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
                             and id <= ?
                           order by id limit ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LlmUsageRetentionPolicy retentionPolicy;
    private final LlmUsageRollupService rollupService;

    public LlmUsageRetentionSweeper(JdbcTemplate jdbcTemplate,
            LlmUsageRetentionPolicy retentionPolicy, LlmUsageRollupService rollupService) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionPolicy = retentionPolicy;
        this.rollupService = rollupService;
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "40 4 * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        LocalDate cutoff = retentionPolicy.sweptBefore();
        if (cutoff == null) {
            return;
        }
        long watermark = rollupService.currentWatermark();
        int deleted = 0;
        int affected;
        do {
            affected = jdbcTemplate.update(DELETE_SQL, cutoff, watermark, BATCH_SIZE);
            deleted += affected;
        } while (affected == BATCH_SIZE);
        if (deleted > 0) {
            log.info("llm usage retention sweep deleted {} event(s) before {}", deleted, cutoff);
        }
    }
}
