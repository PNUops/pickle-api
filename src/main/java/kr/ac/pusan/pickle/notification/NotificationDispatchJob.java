package kr.ac.pusan.pickle.notification;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.mail.MailHtmlLayout;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MailSender;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>The HTML part is built here, at send time; what the row stores stays the
 * plain text the console inbox shows.</p>
 */
@Component
public class NotificationDispatchJob {

    static final String JOB_ID = "notification-dispatcher";
    static final int MAX_ATTEMPTS = 3;
    static final int BATCH_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchJob.class);

    private static final List<Duration> BACKOFFS =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

    private static final String MAIL_FOOTER = "\n\n" + MailHtmlLayout.TEXT_SIGNATURE + "\n";

    private record PendingMail(long id, int attempts, String event, String title, String body,
                               String linkPath, String email, String userStatus) {
    }

    /**
     * What the action button says, by event. The default names the
     * destination generically; an entry here names what the reader does when
     * they get there, which is what the account mails have always done.
     *
     * <p>Keyed by the stored string, not by {@link NotificationEvent}: the
     * column holds the <em>rendered</em> id, so an expiry notice is filed as
     * {@code vm.expiry.d7} and would not resolve back to a constant.</p>
     */
    private static final Map<String, String> CTA_LABELS = Map.of(
            "request.submitted", "신청 확인하기",
            "request.approved", "신청 확인하기",
            "request.rejected", "신청 확인하기",
            "vm.create.done", "VM 확인하기",
            "relay.contact_lost", "관리자 콘솔에서 확인",
            "relay.never_contacted", "관리자 콘솔에서 확인",
            "relay.band_usage_high", "관리자 콘솔에서 확인",
            "cert.failure", "관리자 콘솔에서 확인",
            "campus_ip.requested", "관리자 콘솔에서 확인");

    private static final String DEFAULT_CTA_LABEL = "콘솔에서 확인";

    private final JdbcTemplate jdbcTemplate;
    private final MailSender mailSender;
    private final String consoleBaseUrl;

    public NotificationDispatchJob(JdbcTemplate jdbcTemplate, MailSender mailSender,
            @Value("${pickle.console.base-url:https://pickle.pusan.ac.kr}") String consoleBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
        String base = consoleBaseUrl == null || consoleBaseUrl.isBlank()
                ? "https://pickle.pusan.ac.kr" : consoleBaseUrl;
        this.consoleBaseUrl = base.replaceAll("/+$", ""); // link paths start with '/'
    }

    @Recurring(id = JOB_ID, interval = "PT1M")
    @Job(name = JOB_ID, retries = 0)
    public void dispatch() {
        List<PendingMail> due = jdbcTemplate.query("""
                select n.id, n.attempts, n.event, n.title, n.body, n.link_path,
                       u.email, u.status as user_status
                  from notifications n
                  join users u on u.id = n.user_id
                 where n.status = 'PENDING' and n.next_attempt_at <= now()
                 order by n.next_attempt_at
                 limit %d
                """.formatted(BATCH_SIZE),
                (rs, rowNum) -> new PendingMail(rs.getLong("id"), rs.getInt("attempts"),
                        rs.getString("event"), rs.getString("title"), rs.getString("body"),
                        rs.getString("link_path"), rs.getString("email"),
                        rs.getString("user_status")));
        for (PendingMail mail : due) {
            // Recipient deactivated between enqueue and send (publish resolves
            // ACTIVE at insert time) — never mail a closed account; SKIPPED
            // keeps the delivery log honest instead of an eternal PENDING.
            if (!"ACTIVE".equals(mail.userStatus())) {
                jdbcTemplate.update("""
                        update notifications
                           set status = 'SKIPPED', last_error = '수신자 계정 비활성(발송 생략)'
                         where id = ? and status = 'PENDING'
                        """, mail.id());
                continue;
            }
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
                        textPart(mail), htmlPart(mail)));
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

    /**
     * The plain-text part: body, the URL on a line of its own, signature. The
     * link line is what a reader without HTML has to work with — without it
     * the action existed only in the alternative they cannot see. Safe to
     * expose to link-prefetching gateways because a notification link is a
     * plain console path, never a one-time token.
     */
    private String textPart(PendingMail mail) {
        String link = mail.linkPath() == null || mail.linkPath().isBlank()
                ? null : consoleBaseUrl + mail.linkPath();
        return link == null
                ? mail.body() + MAIL_FOOTER
                : mail.body() + "\n\n" + link + MAIL_FOOTER;
    }

    /**
     * The branded HTML part, or null to fall back to text alone — a layout
     * bug must cost this mail its styling, not the whole batch its delivery.
     */
    private String htmlPart(PendingMail mail) {
        try {
            MailHtmlLayout.Cta cta = mail.linkPath() == null || mail.linkPath().isBlank()
                    ? null
                    : new MailHtmlLayout.Cta(ctaLabel(mail), consoleBaseUrl + mail.linkPath());
            return MailHtmlLayout.render(mail.title(), mail.body(), cta);
        } catch (RuntimeException e) {
            log.warn("notification {} html render failed, sending text only: {}",
                    mail.id(), e.toString());
            return null;
        }
    }

    /** The action label for this mail. An approved LLM-key request is the one
     *  case the event alone cannot answer — every kind shares
     *  {@code request.approved}, and only that one is sent to the screen that
     *  issues the key, so the destination decides. */
    private static String ctaLabel(PendingMail mail) {
        if (mail.linkPath() != null && mail.linkPath().startsWith("/console/llm-keys/")) {
            return "키 발급하기";
        }
        return CTA_LABELS.getOrDefault(mail.event(), DEFAULT_CTA_LABEL);
    }

    private static String summarize(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
