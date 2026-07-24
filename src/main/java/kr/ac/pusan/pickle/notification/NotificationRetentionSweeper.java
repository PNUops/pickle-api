package kr.ac.pusan.pickle.notification;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Daily retention sweep (04:30 KST) that deletes notifications older than
 * {@code settings.notification_retention_days}. Deletes are batched in a
 * bounded LIMIT loop so a large backlog never holds a long lock. This touches
 * ONLY the {@code notifications} table — never {@code audit_logs}/
 * {@code vm_events}, which are permanent records.
 */
@Component
public class NotificationRetentionSweeper {

    static final String JOB_ID = "notification-retention-sweeper";
    static final int DEFAULT_RETENTION_DAYS = 365;
    private static final int BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionSweeper.class);

    private final JdbcTemplate jdbcTemplate;
    private final SettingsService settingsService;

    public NotificationRetentionSweeper(JdbcTemplate jdbcTemplate, SettingsService settingsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.settingsService = settingsService;
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "30 4 * * *", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        int retentionDays = settingsService.integer(
                SettingsService.NOTIFICATION_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(retentionDays)));
        int deleted = 0;
        int affected;
        do {
            // ONLY notifications — audit_logs / vm_events are permanent and must
            // never be swept here.
            affected = jdbcTemplate.update("""
                    delete from notifications
                     where id in (select id from notifications
                                   where created_at < ? order by id limit ?)
                    """, cutoff, BATCH_SIZE);
            deleted += affected;
        } while (affected == BATCH_SIZE);
        if (deleted > 0) {
            log.info("notification retention sweep deleted {} row(s) older than {} day(s)",
                    deleted, retentionDays);
        }
    }
}
