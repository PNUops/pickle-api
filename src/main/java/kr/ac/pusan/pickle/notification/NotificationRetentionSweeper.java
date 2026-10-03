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
 * Only terminal inbox rows are removed, after preserving their permanent
 * delivery metadata. Pending/in-flight execution, audit and VM events remain.
 */
@Component
public class NotificationRetentionSweeper {

    static final String JOB_ID = "notification-retention-sweeper";
    static final int DEFAULT_RETENTION_DAYS = 365;
    private static final int BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionSweeper.class);

    private final JdbcTemplate jdbcTemplate;
    private final SettingsService settingsService;
    private final kr.ac.pusan.pickle.mail.MailDeliveryJournal journal;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public NotificationRetentionSweeper(JdbcTemplate jdbcTemplate, SettingsService settingsService, kr.ac.pusan.pickle.mail.MailDeliveryJournal journal,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.settingsService = settingsService;
        this.journal = journal;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
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
            affected = transactions.execute(tx -> {
                var ids = jdbcTemplate.queryForList("""
                        select id from notifications where created_at < ?
                          and status in ('SENT', 'FAILED', 'SKIPPED', 'UNKNOWN')
                         order by id limit ? for update skip locked
                        """, Long.class, cutoff, BATCH_SIZE);
                if (ids.isEmpty()) return 0;
                journal.importNotifications(ids);
                String marks = "?,".repeat(ids.size());
                return jdbcTemplate.update("delete from notifications where id in ("
                        + marks.substring(0, marks.length() - 1) + ")", ids.toArray());
            });
            deleted += affected;
        } while (affected == BATCH_SIZE);
        if (deleted > 0) {
            log.info("notification retention sweep deleted {} row(s) older than {} day(s)",
                    deleted, retentionDays);
        }
    }
}
