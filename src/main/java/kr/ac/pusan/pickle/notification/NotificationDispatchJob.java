package kr.ac.pusan.pickle.notification;

import java.time.Duration;
import java.util.List;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MailSender;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Self-recovering email dispatcher: every minute it drains due {@code PENDING}
 * notifications (batch 100, oldest due first). Each row is claimed with a CAS
 * ({@code attempts++} guarded on {@code status='PENDING'}) so concurrent runs
 * never double-send; a send failure backs off 1m/5m and parks the row
 * {@code FAILED} after {@value #MAX_ATTEMPTS} attempts (the SYS_ADMIN delivery
 * log resends from there). Per-row errors are swallowed — one bad recipient
 * never stalls the batch.
 */
@Component
public class NotificationDispatchJob {

    static final String JOB_ID = "notification-dispatcher";
    static final int MAX_ATTEMPTS = 3;
    static final int BATCH_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchJob.class);

    private static final List<Duration> BACKOFFS =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

    private static final String MAIL_FOOTER = "\n\n— Pickle 운영팀\n";

    private record PendingMail(long id, int attempts, String title, String body, String email) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final MailSender mailSender;

    public NotificationDispatchJob(JdbcTemplate jdbcTemplate, MailSender mailSender) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
    }

    @Recurring(id = JOB_ID, interval = "PT1M")
    @Job(name = JOB_ID, retries = 0)
    public void dispatch() {
        List<PendingMail> due = jdbcTemplate.query("""
                select n.id, n.attempts, n.title, n.body, u.email
                  from notifications n
                  join users u on u.id = n.user_id
                 where n.status = 'PENDING' and n.next_attempt_at <= now()
                 order by n.next_attempt_at
                 limit %d
                """.formatted(BATCH_SIZE),
                (rs, rowNum) -> new PendingMail(rs.getLong("id"), rs.getInt("attempts"),
                        rs.getString("title"), rs.getString("body"), rs.getString("email")));
        for (PendingMail mail : due) {
            // CAS claim — a concurrent run (or a resend) that got here first wins.
            if (jdbcTemplate.update("""
                    update notifications set attempts = attempts + 1
                     where id = ? and status = 'PENDING'
                    """, mail.id()) == 0) {
                continue;
            }
            int attempt = mail.attempts() + 1;
            try {
                mailSender.send(new MailMessage(mail.email(), "[Pickle] " + mail.title(),
                        mail.body() + MAIL_FOOTER));
                jdbcTemplate.update("""
                        update notifications set status = 'SENT', sent_at = now(), last_error = null
                         where id = ?
                        """, mail.id());
            } catch (RuntimeException e) {
                String error = summarize(e);
                if (attempt >= MAX_ATTEMPTS) {
                    jdbcTemplate.update("""
                            update notifications set status = 'FAILED', last_error = ?
                             where id = ?
                            """, error, mail.id());
                    log.warn("notification {} failed permanently after {} attempts: {}",
                            mail.id(), attempt, error);
                } else {
                    Duration backoff = BACKOFFS.get(Math.min(attempt, BACKOFFS.size()) - 1);
                    jdbcTemplate.update("""
                            update notifications
                               set next_attempt_at = now() + ?::interval, last_error = ?
                             where id = ?
                            """, backoff.toSeconds() + " seconds", error, mail.id());
                    log.info("notification {} send failed (attempt {}), retrying in {}: {}",
                            mail.id(), attempt, backoff, error);
                }
            }
        }
    }

    private static String summarize(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
