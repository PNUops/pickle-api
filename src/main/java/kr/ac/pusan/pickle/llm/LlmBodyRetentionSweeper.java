package kr.ac.pusan.pickle.llm;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes captured prompt and response text once it is 30 days old (04:50
 * KST), in bounded batches.
 *
 * <p><b>The 30 days is a constant here and nowhere else.</b> The operator's
 * decision was "fixed, not configurable", and every mechanism that could
 * express a different value eventually expresses one: a settings row would
 * surface as an editable field on the administrator screen, which is the
 * decision's own opposite. {@link LlmUsageRetentionPolicy} is a class instead
 * of a constant because two components — the sweep and the rollup — must agree
 * on a changeable answer. Nothing here has a second reader.
 *
 * <p><b>This does not inherit the usage sweep's 90-day floor, and must not.</b>
 * That floor exists because the gateway spools usage events to disk and
 * re-sends anything it could not confirm: forgetting one sooner than the
 * gateway does means a lost checkpoint counts it twice. Captured bodies have
 * none of that. They are held in memory and posted directly, a refused batch
 * is dropped rather than retried, and there is no rollup to rebuild. Raising
 * 30 to 90 "for consistency" would keep prompt text three times as long for a
 * reason that does not apply to it.
 *
 * <p>The cutoff is an instant, not a KST day boundary: the usage sweep buckets
 * by calendar day because its unit is a day, and a body has no bucket.
 */
@Component
public class LlmBodyRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(LlmBodyRetentionSweeper.class);

    static final String JOB_ID = "llm-body-retention-sweeper";

    /** Operator decision, 2026-09-04. Fixed; deliberately not a setting. */
    static final int RETENTION_DAYS = 30;

    private static final int BATCH_SIZE = 1000;

    /**
     * Deletes on either timestamp, and needs both. {@code requested_at} alone
     * cannot keep the promise: a gateway with a skewed clock reports a time in
     * the future and that row would never expire. {@code received_at} alone
     * would delete on a column the promise is not written in.
     */
    private static final String DELETE_SQL = """
            delete from llm_request_bodies
             where id in (select id from llm_request_bodies
                           where requested_at < ? or received_at < ?
                           order by id limit ?)
            """;

    private static final String REMAINING_SQL = """
            select count(*) from llm_request_bodies
             where requested_at < ? or received_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public LlmBodyRetentionSweeper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "50 4 * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        java.sql.Timestamp at = java.sql.Timestamp.from(cutoff);
        int deleted = 0;
        int affected;
        do {
            affected = jdbcTemplate.update(DELETE_SQL, at, at, BATCH_SIZE);
            deleted += affected;
        } while (affected == BATCH_SIZE);
        // Logged every run, deleted or not. The sibling sweepers log only when
        // they removed something, which makes "has never run" and "had nothing
        // to remove" produce identical evidence — and the first of those is
        // how a retention promise quietly becomes indefinite storage.
        log.info("llm body retention sweep deleted {} record(s) older than {} days",
                deleted, RETENTION_DAYS);
        // A predicate that matches nothing looks exactly like a clean sweep
        // from the outside. Counting what is left is the only thing that tells
        // the two apart.
        Long remaining = jdbcTemplate.queryForObject(REMAINING_SQL, Long.class, at, at);
        if (remaining != null && remaining > 0) {
            log.warn("llm body retention sweep left {} record(s) past the cutoff", remaining);
        }
    }
}
