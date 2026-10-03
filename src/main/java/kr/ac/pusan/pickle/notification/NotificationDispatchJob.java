package kr.ac.pusan.pickle.notification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import kr.ac.pusan.pickle.mail.MailDeliveryJournal;
import kr.ac.pusan.pickle.mail.MailDeliveryFailure;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 * Every minute, due notifications are claimed as SENDING before SMTP. A
 * known rejection backs off 1m/5m and parks FAILED after three attempts.
 * Ambiguous outcomes and interrupted claims become UNKNOWN, never an automatic
 * resend. Attempt evidence is separate from the expiring console inbox.
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
                               String linkPath, String email, String userStatus, boolean frozenRecipient) {
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
                   coalesce(n.recipient_email, u.email) as email, u.status as user_status,
                   (n.recipient_email is not null) as frozen_recipient
              from notifications n
              join users u on u.id = n.user_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final MailSender mailSender;
    private final String consoleBaseUrl;
    private final Duration bundleWindow;
    private final MailDeliveryJournal journal;
    private final TransactionTemplate transactions;

    public NotificationDispatchJob(JdbcTemplate jdbcTemplate, MailSender mailSender,
            @Value("${pickle.console.base-url:https://pickle.pusan.ac.kr}") String consoleBaseUrl,
            @Value("${pickle.notification.admin-bundle-window:PT60M}") Duration bundleWindow,
            MailDeliveryJournal journal, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
        String base = consoleBaseUrl == null || consoleBaseUrl.isBlank()
                ? "https://pickle.pusan.ac.kr" : consoleBaseUrl;
        this.consoleBaseUrl = base.replaceAll("/+$", ""); // link paths start with '/'
        this.bundleWindow = bundleWindow;
        this.journal = journal;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Recurring(id = JOB_ID, interval = "PT1M")
    @Job(name = JOB_ID, retries = 0)
    public void dispatch() {
        journal.importLegacyBatch(1000);
        reconcileExpiredClaims();
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
            UUID owner = UUID.randomUUID();
            if (claim(List.of(mail), owner).isEmpty()) {
                continue;
            }
            send(List.of(mail), owner, mail.email(), "[Pickle] " + mail.title(), textPart(mail),
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
                   and (n.attempts > 0 or not exists (
                       select 1 from notifications s
                        where s.user_id = n.user_id and s.bundle
                          and (s.recipient_email is null or s.recipient_email = coalesce(n.recipient_email,
                            (select email from users where id = n.user_id)))
                          and s.sent_at > now() - ?::interval))
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
            List<PendingMail> eligible = waiting.stream().filter(mail -> !skipIfInactive(mail)).toList();
            var addresses = eligible.stream().collect(java.util.stream.Collectors.groupingBy(PendingMail::email));
            for (List<PendingMail> addressRows : addresses.values()) {
                UUID owner = UUID.randomUUID();
                List<PendingMail> claimed = claim(addressRows, owner);
                if (claimed.isEmpty()) {
                    continue;
                }
                String email = claimed.getFirst().email();
                if (claimed.size() == 1) {
                    PendingMail mail = claimed.getFirst();
                    send(claimed, owner, email, "[Pickle] " + mail.title(), textPart(mail), htmlPart(mail));
                } else {
                    Bundle bundle = bundle(claimed);
                    send(claimed, owner, email, "[Pickle] " + bundle.title(), bundleText(bundle),
                            bundleHtml(bundle, claimed));
                }
            }
        }
    }

    private static PendingMail mapPending(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PendingMail(rs.getLong("id"), rs.getInt("attempts"),
                rs.getString("event"), rs.getString("title"), rs.getString("body"),
                rs.getString("link_path"), rs.getString("email"),
                rs.getString("user_status"), rs.getBoolean("frozen_recipient"));
    }

    /**
     * Legacy recipients are checked for activation. Request snapshots preserve
     * the enqueue-time decision and address, including later account changes.
     */
    private boolean skipIfInactive(PendingMail mail) {
        if (mail.frozenRecipient() || "ACTIVE".equals(mail.userStatus())) {
            return false;
        }
        transactions.executeWithoutResult(tx -> {
            int changed = jdbcTemplate.update("""
                    update notifications set status = 'SKIPPED', skip_reason = 'ACCOUNT_INACTIVE',
                        last_error = 'ACCOUNT_INACTIVE'
                     where id = ? and status = 'PENDING'
                    """, mail.id());
            if (changed > 0) journal.recordNotificationSkip(mail.id(), "ACCOUNT_INACTIVE");
        });
        return true;
    }

    /** Claims one outbound message in a short transaction; SMTP runs after commit. */
    private List<PendingMail> claim(List<PendingMail> rows, UUID owner) {
        if (rows.isEmpty()) return List.of();
        return transactions.execute(tx -> {
            long userId = jdbcTemplate.queryForObject("select user_id from notifications where id = ?",
                    Long.class, rows.getFirst().id());
            jdbcTemplate.queryForObject("select pg_advisory_xact_lock(1129074512, ?::integer)::text",
                    String.class, userId % Integer.MAX_VALUE);
            int active = jdbcTemplate.queryForObject("""
                    select count(*) from notifications where user_id = ? and bundle
                      and status = 'SENDING'
                    """, Integer.class, userId);
            boolean bundled = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "select bundle from notifications where id = ?", Boolean.class, rows.getFirst().id()));
            if (bundled && active > 0) return List.of();
            String placeholders = "(?, ?, ?::text), ".repeat(rows.size() - 1) + "(?, ?, ?::text)";
            List<Object> args = new ArrayList<>();
            args.add(owner);
            for (PendingMail row : rows) { args.add(row.id()); args.add(row.attempts()); args.add(row.email()); }
            args.add(bundleWindow.toSeconds() + " seconds");
            List<Long> won = jdbcTemplate.queryForList("""
                    update notifications n set attempts = n.attempts + 1, status = 'SENDING',
                        delivery_claim_id = ?, delivery_claimed_at = now(),
                        recipient_email = coalesce(n.recipient_email, picked.email)
                      from (values %s) as picked(id, attempts, email)
                     where n.id = picked.id and n.attempts = picked.attempts and n.status = 'PENDING'
                       and (not n.bundle or n.attempts > 0 or not exists (
                         select 1 from notifications prior where prior.user_id = n.user_id and prior.bundle
                           and (prior.recipient_email is null or prior.recipient_email = picked.email)
                           and prior.sent_at > now() - ?::interval))
                    returning n.id
                    """.formatted(placeholders), Long.class, args.toArray());
            journal.beginNotificationAttempt(won, owner);
            Set<Long> ids = Set.copyOf(won);
            return rows.stream().filter(row -> ids.contains(row.id())).toList();
        });
    }

    private void reconcileExpiredClaims() {
        transactions.executeWithoutResult(tx -> {
            var expired = jdbcTemplate.queryForList("""
                    select id, delivery_claim_id from notifications
                     where status = 'SENDING' and delivery_claimed_at < now() - interval '10 minutes'
                     order by delivery_claimed_at limit 100 for update skip locked
                    """);
            for (var row : expired) {
                long id = ((Number) row.get("id")).longValue();
                UUID owner = (UUID) row.get("delivery_claim_id");
                if (owner == null) continue;
                int changed = jdbcTemplate.update("""
                        update notifications set status = 'UNKNOWN', last_error = 'ATTEMPT_INTERRUPTED'
                         where id = ? and delivery_claim_id = ? and status = 'SENDING'
                           and delivery_claimed_at < now() - interval '10 minutes'
                        """, id, owner);
                if (changed > 0) journal.finishNotificationAttempt(List.of(id), owner, "UNKNOWN", "ATTEMPT_INTERRUPTED", null);
            }
        });
    }

    private void send(List<PendingMail> rows, UUID owner, String email, String subject, String text,
            String html) {
        try {
            mailSender.send(new MailMessage(email, subject, text, html));
        } catch (RuntimeException failure) {
            var result = MailDeliveryFailure.classify(failure);
            if (!result.definiteFailure()) {
                complete(rows, owner, "UNKNOWN", result.code(), null);
                return;
            }
            for (PendingMail row : rows) {
                int attempt = row.attempts() + 1;
                boolean exhausted = attempt >= MAX_ATTEMPTS;
                Instant next = exhausted ? null : Instant.now().plus(BACKOFFS.get(attempt - 1));
                complete(List.of(row), owner, exhausted ? "FAILED" : "PENDING", result.code(), next);
            }
            return;
        }
        try {
            complete(rows, owner, "SENT", null, null);
        } catch (RuntimeException resultFailure) {
            // A successful SMTP handoff must never become an automatic retry.
            log.warn("mail dispatch {} result persistence failed; delivery is unconfirmed", owner);
            try {
                complete(rows, owner, "UNKNOWN", "RESULT_NOT_RECORDED", null);
            } catch (RuntimeException unavailableDatabase) {
                // SENDING remains claimed until reconciliation marks it UNKNOWN.
                log.warn("mail dispatch {} remains claimed until reconciliation", owner);
            }
        }
    }

    private void complete(List<PendingMail> rows, UUID owner, String state, String code, Instant next) {
        transactions.executeWithoutResult(tx -> {
            List<Long> updated = new ArrayList<>();
            for (PendingMail row : rows) {
                int changed = jdbcTemplate.update("""
                        update notifications set status = ?::notification_status, last_error = ?,
                            next_attempt_at = coalesce(?::timestamptz, next_attempt_at),
                            sent_at = case when ? = 'SENT' then now() else sent_at end
                         where id = ? and delivery_claim_id = ? and status in ('SENDING', 'UNKNOWN')
                        """, state, code, next == null ? null : java.sql.Timestamp.from(next), state, row.id(), owner);
                if (changed > 0) updated.add(row.id());
            }
            journal.finishNotificationAttempt(updated, owner, state, code, next);
        });
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


}
