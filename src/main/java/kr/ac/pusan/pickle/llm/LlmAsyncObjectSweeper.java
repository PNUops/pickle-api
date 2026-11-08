package kr.ac.pusan.pickle.llm;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code llm_async_objects} from growing without end (05:10 KST).
 *
 * <p>Three passes, each bounded:
 * <ul>
 * <li>An owned row whose vendor-stated lifetime has passed is released as
 * {@code VENDOR_GONE}. Only batches carry one today: the vendor deletes a
 * batch 30 days after it was submitted and answers 410 from then on, so the
 * row would otherwise stay owned for an object nobody can reach.</li>
 * <li>A batch's {@code PENDING} row older than {@link #PENDING_DAYS} is
 * deleted. By then it is either a ghost (the api committed an intent the
 * gateway had given up on) or an unconfirmed submit whose object the vendor
 * has deleted anyway, 30 days after submission. Other kinds keep theirs:
 * their objects outlive that, and the row's event id is what a
 * reconciliation against the vendor joins on.</li>
 * <li>A released row older than {@link #RELEASED_DAYS} is deleted. It is
 * kept that long as the record of what a key once owned, which is the
 * window the usage sweep's floor also keeps.</li>
 * </ul>
 *
 * <p>An owned row without an expiry (a video, whose retention the vendor does
 * not document) is never removed on time alone: deleting it before the vendor
 * does would cost the owner a live result. It is released when the vendor
 * answers 404 or 410 for it.
 *
 * <p>The periods are constants, for the reason {@link LlmBodyRetentionSweeper}
 * gives.
 */
@Component
public class LlmAsyncObjectSweeper {

    private static final Logger log = LoggerFactory.getLogger(LlmAsyncObjectSweeper.class);

    static final String JOB_ID = "llm-async-object-sweeper";

    static final int PENDING_DAYS = 30;

    static final int RELEASED_DAYS = 90;

    private static final int BATCH_SIZE = 1000;

    private static final String EXPIRE_SQL = """
            update llm_async_objects
               set state = 'RELEASED', release_reason = 'VENDOR_GONE', released_at = ?
             where id in (select id from llm_async_objects
                           where state = 'OWNED' and expires_at < ?
                           order by id limit ?)
            """;

    private static final String PENDING_SQL = """
            delete from llm_async_objects
             where id in (select id from llm_async_objects
                           where state = 'PENDING' and kind = 'BATCH' and created_at < ?
                           order by id limit ?)
            """;

    private static final String RELEASED_SQL = """
            delete from llm_async_objects
             where id in (select id from llm_async_objects
                           where state = 'RELEASED' and released_at < ?
                           order by id limit ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public LlmAsyncObjectSweeper(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "10 5 * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);
        int expired = drain(EXPIRE_SQL, at, at);
        int pending = drain(PENDING_SQL, Timestamp.from(now.minus(PENDING_DAYS, ChronoUnit.DAYS)));
        int released = drain(RELEASED_SQL,
                Timestamp.from(now.minus(RELEASED_DAYS, ChronoUnit.DAYS)));
        // Logged every run, as the body sweep does, so "never ran" and "had
        // nothing to do" leave different evidence.
        log.info("llm async object sweep: {} expired, {} stale pending and {} old released row(s)",
                expired, pending, released);
    }

    private int drain(String sql, Object... args) {
        Object[] withLimit = java.util.Arrays.copyOf(args, args.length + 1);
        withLimit[args.length] = BATCH_SIZE;
        int total = 0;
        int affected;
        do {
            affected = jdbcTemplate.update(sql, withLimit);
            total += affected;
        } while (affected == BATCH_SIZE);
        return total;
    }
}
