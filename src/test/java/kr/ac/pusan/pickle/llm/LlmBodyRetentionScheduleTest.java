package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * That the retention sweeps are actually scheduled, not merely written.
 *
 * <p>Every sweep in this codebase is exercised by calling its method directly,
 * so removing an {@code @Recurring} annotation left the suite green and the job
 * silently unscheduled. For a retention sweep that failure has a specific
 * shape: nothing breaks, nothing is logged, and a bounded promise quietly
 * becomes indefinite storage. This asserts the schedule itself.
 *
 * <p>{@code trim} is not cosmetic -- {@code jobrunr_recurring_jobs.id} is a
 * blank-padded CHAR, so an untrimmed comparison silently matches nothing and
 * the guard passes for the wrong reason.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmBodyRetentionScheduleTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void everyRetentionSweepIsRegisteredAsARecurringJob() {
        List<String> ids = jdbcTemplate.queryForList(
                "select trim(id) from jobrunr_recurring_jobs", String.class);

        assertThat(ids).contains(
                LlmBodyRetentionSweeper.JOB_ID,
                "llm-usage-retention-sweeper",
                "notification-retention-sweeper",
                "auth-token-retention-sweeper");
    }
}
