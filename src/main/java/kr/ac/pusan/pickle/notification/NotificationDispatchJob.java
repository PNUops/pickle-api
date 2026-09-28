package kr.ac.pusan.pickle.notification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 * <p>Rows marked {@code bundle} (mail to an administrator, V133) are sent per
 * recipient instead: when the recipient's last such mail is older than the
 * bundle window, or there was none, everything waiting for them goes out as
 * one mail. So the first one after a quiet spell leaves on the next run and
 * whatever follows waits for the window to close. A single waiting row is
 * sent as it would have been; several become one summary.</p>
 *
 * <p>The HTML part is built here, at send time; what the row stores stays the
 * plain text the console inbox shows.</p>
 */
@Component
public class NotificationDispatchJob {

    static final String JOB_ID = "notification-dispatcher";
    static final int MAX_ATTEMPTS = 3;
    static final int BATCH_SIZE = 100;
    /** Rows folded into one summary mail; the rest wait for the next window. */
    static final int BUNDLE_MAX_ROWS = 200;
    /** Distinct lines a summary lists before counting the remainder. */
    static final int BUNDLE_MAX_LINES = 20;

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchJob.class);

    private static final List<Duration> BACKOFFS =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

    private static final String MAIL_FOOTER = "\n\n" + MailHtmlLayout.TEXT_SIGNATURE + "\n";

    record PendingMail(long id, int attempts, String event, String title, String body,
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
     *
     * <p>Only events whose destination is the same for every recipient belong
     * here. Where it is not — {@code request.submitted} goes to the admin
     * queue or to the requester's own page, depending on who is being told —
     * the destination decides instead, in {@link #ctaLabel}.</p>
     */
    private static final Map<String, String> CTA_LABELS = Map.of(
            "request.submitted", "신청 확인하기",
            "request.approved", "신청 확인하기",
            "request.rejected", "신청 확인하기",
            "vm.create.done", "VM 확인하기");

    private static final String ADMIN_CTA_LABEL = "관리자 콘솔에서 확인";

    private static final String DEFAULT_CTA_LABEL = "콘솔에서 확인";

    private static final String PENDING_MAIL_COLUMNS = """
            select n.id, n.attempts, n.event, n.title, n.body, n.link_path,
                   u.email, u.status as user_status
              from notifications n
              join users u on u.id = n.user_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final MailSender mailSender;
    private final String consoleBaseUrl;
    private final Duration bundleWindow;

    public NotificationDispatchJob(JdbcTemplate jdbcTemplate, MailSender mailSender,
            @Value("${pickle.console.base-url:https://pickle.pusan.ac.kr}") String consoleBaseUrl,
            @Value("${pickle.notification.admin-bundle-window:PT60M}") Duration bundleWindow) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
        String base = consoleBaseUrl == null || consoleBaseUrl.isBlank()
                ? "https://pickle.pusan.ac.kr" : consoleBaseUrl;
        this.consoleBaseUrl = base.replaceAll("/+$", ""); // link paths start with '/'
        this.bundleWindow = bundleWindow;
    }

    @Recurring(id = JOB_ID, interval = "PT1M")
    @Job(name = JOB_ID, retries = 0)
    public void dispatch() {
        List<PendingMail> due = jdbcTemplate.query(PENDING_MAIL_COLUMNS + """
                 where n.status = 'PENDING' and not n.bundle and n.next_attempt_at <= now()
                 order by n.next_attempt_at
                 limit %d
                """.formatted(BATCH_SIZE), NotificationDispatchJob::mapPending);
        for (PendingMail mail : due) {
            if (skipIfInactive(mail)) {
                continue;
            }
            // CAS claim — a concurrent run (or a resend) that got here first wins.
            if (claim(List.of(mail)).isEmpty()) {
                continue;
            }
            send(List.of(mail), mail.email(), "[Pickle] " + mail.title(), textPart(mail),
                    htmlPart(mail));
        }
        dispatchBundles();
    }

    /**
     * One mail per recipient whose window has closed. The window is measured
     * from the last bundled mail actually sent to that recipient, so a burst
     * costs them the first mail at once and one more when the window ends.
     */
    private void dispatchBundles() {
        List<Long> recipients = jdbcTemplate.queryForList("""
                select n.user_id
                  from notifications n
                 where n.status = 'PENDING' and n.bundle and n.next_attempt_at <= now()
                   and not exists (
                       select 1 from notifications s
                        where s.user_id = n.user_id and s.bundle
                          and s.sent_at > now() - ?::interval)
                 group by n.user_id
                 order by min(n.next_attempt_at)
                 limit %d
                """.formatted(BATCH_SIZE), Long.class, bundleWindow.toSeconds() + " seconds");
        for (Long userId : recipients) {
            List<PendingMail> waiting = jdbcTemplate.query(PENDING_MAIL_COLUMNS + """
                     where n.user_id = ? and n.status = 'PENDING' and n.bundle
                       and n.next_attempt_at <= now()
                     order by n.created_at, n.id
                     limit %d
                    """.formatted(BUNDLE_MAX_ROWS), NotificationDispatchJob::mapPending, userId);
            if (waiting.isEmpty()) {
                continue;
            }
            if (!"ACTIVE".equals(waiting.getFirst().userStatus())) {
                waiting.forEach(this::skipIfInactive);
                continue;
            }
            List<PendingMail> claimed = claim(waiting);
            if (claimed.isEmpty()) {
                continue;
            }
            String email = claimed.getFirst().email();
            if (claimed.size() == 1) {
                PendingMail mail = claimed.getFirst();
                send(claimed, email, "[Pickle] " + mail.title(), textPart(mail), htmlPart(mail));
            } else {
                Bundle bundle = bundle(claimed);
                send(claimed, email, "[Pickle] " + bundle.title(), bundleText(bundle),
                        bundleHtml(bundle, claimed));
            }
        }
    }

    private static PendingMail mapPending(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PendingMail(rs.getLong("id"), rs.getInt("attempts"),
                rs.getString("event"), rs.getString("title"), rs.getString("body"),
                rs.getString("link_path"), rs.getString("email"),
                rs.getString("user_status"));
    }

    /**
     * Recipient deactivated between enqueue and send (publish resolves ACTIVE
     * at insert time) — never mail a closed account; SKIPPED keeps the
     * delivery log honest instead of an eternal PENDING.
     */
    private boolean skipIfInactive(PendingMail mail) {
        if ("ACTIVE".equals(mail.userStatus())) {
            return false;
        }
        jdbcTemplate.update("""
                update notifications
                   set status = 'SKIPPED', last_error = '수신자 계정 비활성(발송 생략)'
                 where id = ? and status = 'PENDING'
                """, mail.id());
        return true;
    }

    /**
     * CAS claim of the given rows: {@code attempts++} guarded on PENDING and on
     * the attempt count this run read. The status alone is not a guard — a
     * claim leaves it PENDING, so an overlapping run would re-match the row
     * after the first commits and send it again. Returns the rows this run
     * won, in the order given; a concurrent run or a resend that got to one
     * first keeps it.
     */
    private List<PendingMail> claim(List<PendingMail> rows) {
        String placeholders = "(?, ?), ".repeat(rows.size() - 1) + "(?, ?)";
        Object[] args = rows.stream()
                .flatMap(row -> java.util.stream.Stream.of(row.id(), row.attempts()))
                .toArray();
        Set<Long> won = Set.copyOf(jdbcTemplate.queryForList("""
                update notifications set attempts = attempts + 1
                 where (id, attempts) in (%s) and status = 'PENDING'
                returning id
                """.formatted(placeholders), Long.class, args));
        return rows.stream().filter(row -> won.contains(row.id())).toList();
    }

    /**
     * Sends one mail on behalf of the given claimed rows and records the
     * outcome on every one of them: all SENT together, or each backed off (or
     * parked FAILED) by its own attempt count.
     */
    private void send(List<PendingMail> rows, String email, String subject, String text,
            String html) {
        try {
            mailSender.send(new MailMessage(email, subject, text, html));
            for (PendingMail row : rows) {
                jdbcTemplate.update("""
                        update notifications set status = 'SENT', sent_at = now(), last_error = null
                         where id = ?
                        """, row.id());
            }
        } catch (RuntimeException e) {
            String error = summarize(e);
            for (PendingMail row : rows) {
                int attempt = row.attempts() + 1;
                if (attempt >= MAX_ATTEMPTS) {
                    jdbcTemplate.update("""
                            update notifications set status = 'FAILED', last_error = ?
                             where id = ?
                            """, error, row.id());
                    log.warn("notification {} failed permanently after {} attempts: {}",
                            row.id(), attempt, error);
                } else {
                    Duration backoff = BACKOFFS.get(Math.min(attempt, BACKOFFS.size()) - 1);
                    jdbcTemplate.update("""
                            update notifications
                               set next_attempt_at = now() + ?::interval, last_error = ?
                             where id = ?
                            """, backoff.toSeconds() + " seconds", error, row.id());
                    log.info("notification {} send failed (attempt {}), retrying in {}: {}",
                            row.id(), attempt, backoff, error);
                }
            }
        }
    }

    /** A summary mail's content: its title, its lines and where its button goes. */
    record Bundle(String title, int count, List<String> lines, int unlisted, String linkPath,
                  String ctaLabel) {
    }

    /**
     * Folds several waiting rows into one summary. Rows with the same title
     * share a line with their count — a class filing thirty requests reads as
     * one line, not thirty — in the order the first of each arrived.
     *
     * <p>Titles are folded to one line before they are listed: some carry a
     * name a user chose, and a newline in one must not start a list item of
     * its own in the HTML part.</p>
     *
     * <p>The button goes to the admin request queue when every row is a
     * request, and to the notification inbox otherwise, where each row is
     * listed with its own link.</p>
     */
    static Bundle bundle(List<PendingMail> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PendingMail row : rows) {
            counts.merge(oneLine(row.title()), 1, Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        int unlisted = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (lines.size() < BUNDLE_MAX_LINES) {
                lines.add(entry.getKey() + " " + entry.getValue() + "건");
            } else {
                unlisted += entry.getValue();
            }
        }
        boolean allRequests = rows.stream().allMatch(row -> row.linkPath() != null
                && row.linkPath().startsWith("/admin/requests/"));
        return allRequests
                ? new Bundle("관리자 알림 " + rows.size() + "건", rows.size(), lines, unlisted,
                        "/admin/requests", "신청 확인하기")
                : new Bundle("관리자 알림 " + rows.size() + "건", rows.size(), lines, unlisted,
                        "/console/notifications", "알림 확인하기");
    }

    static String bundleBody(Bundle bundle) {
        String list = bundle.lines().stream().map(line -> "- " + line)
                .collect(Collectors.joining("\n"));
        String rest = bundle.unlisted() > 0
                ? "\n\n이 밖의 알림 " + bundle.unlisted() + "건은 콘솔 알림에서 확인할 수 있습니다."
                : "";
        return "관리자 알림 " + bundle.count() + "건을 한 통으로 모았습니다.\n\n" + list + rest;
    }

    private String bundleText(Bundle bundle) {
        return bundleBody(bundle) + "\n\n" + consoleBaseUrl + bundle.linkPath() + MAIL_FOOTER;
    }

    private String bundleHtml(Bundle bundle, List<PendingMail> rows) {
        try {
            return MailHtmlLayout.render(bundle.title(), bundleBody(bundle),
                    new MailHtmlLayout.Cta(bundle.ctaLabel(), consoleBaseUrl + bundle.linkPath()));
        } catch (RuntimeException e) {
            log.warn("bundled mail for notifications {} html render failed, sending text only: {}",
                    rows.stream().map(PendingMail::id).toList(), e.toString());
            return null;
        }
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
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
        // Stripped: some bodies end with a newline of their own, which would
        // otherwise open a gap between the text and the link.
        String body = mail.body().stripTrailing();
        return link == null ? body + MAIL_FOOTER : body + "\n\n" + link + MAIL_FOOTER;
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

    /**
     * The action label for this mail. Two cases the event alone cannot answer,
     * both settled by where the button actually goes.
     *
     * <p>An approved LLM-key request: every kind shares
     * {@code request.approved} and only that one is sent to the screen that
     * issues the key. Keyed on the event as well as the path, so the first
     * key-lifecycle notice to link there — an expiry or a revocation — does
     * not tell its reader to issue a key they already have.</p>
     *
     * <p>Anything landing in the admin console: {@code request.submitted}
     * reaches both a requester and the reviewers, and every other
     * admin-destined event (relay, certificate, campus IP, GPU review) would
     * otherwise need its own row here and be forgotten the way
     * {@code gpu.review} was.</p>
     */
    private static String ctaLabel(PendingMail mail) {
        String path = mail.linkPath();
        if (path == null) {
            return DEFAULT_CTA_LABEL;
        }
        if ("request.approved".equals(mail.event()) && path.startsWith("/console/llm-keys/")) {
            return "키 발급하기";
        }
        if (path.startsWith("/admin/")) {
            return ADMIN_CTA_LABEL;
        }
        return CTA_LABELS.getOrDefault(mail.event(), DEFAULT_CTA_LABEL);
    }

    private static String summarize(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
