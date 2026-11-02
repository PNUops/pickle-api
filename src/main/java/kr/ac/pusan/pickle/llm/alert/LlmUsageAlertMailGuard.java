package kr.ac.pusan.pickle.llm.alert;

import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.config.LlmUsageAlertProperties;
import kr.ac.pusan.pickle.mail.MailDeliveryJournal;
import kr.ac.pusan.pickle.notification.NotificationPreferencesService;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Current checks apply only to the new usage kind; all legacy snapshots keep their policy. */
@Component
public class LlmUsageAlertMailGuard {
    private final JdbcTemplate jdbc;
    private final LlmUsageAlertAccess access;
    private final NotificationPreferencesService preferences;
    private final LlmUsageAlertProperties properties;
    private final MailDeliveryJournal journal;
    private final TransactionTemplate transactions;

    public LlmUsageAlertMailGuard(JdbcTemplate jdbc, LlmUsageAlertAccess access,
            NotificationPreferencesService preferences, LlmUsageAlertProperties properties,
            MailDeliveryJournal journal, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.access = access; this.preferences = preferences;
        this.properties = properties; this.journal = journal; this.transactions = new TransactionTemplate(manager);
    }

    public @Nullable String rejection(long id) {
        if (!properties.enabled()) { return "USAGE_ALERTS_DISABLED"; }
        List<Long> users = jdbc.query("select user_id from notifications where id=? and event=?",
                (rs, n) -> rs.getLong(1), id, LlmUsageAlertAccess.EVENT);
        if (users.isEmpty() || !access.readableNotification(id, users.getFirst())) { return "USAGE_READ_REVOKED"; }
        if (!preferences.usageMailEnabled(users.getFirst())) { return "USAGE_MAIL_DISABLED"; }
        return null;
    }

    public boolean skipPending(long id) {
        String code = rejection(id);
        if (code == null) { return false; }
        transactions.executeWithoutResult(tx -> {
            int changed = jdbc.update("update notifications set status='SKIPPED',skip_reason=?,last_error=? "
                    + "where id=? and event=? and status='PENDING'", code, code, id, LlmUsageAlertAccess.EVENT);
            if (changed > 0) { journal.recordNotificationSkip(id, code); }
        });
        return true;
    }

    public boolean skipClaimed(long id, UUID owner) {
        String code = rejection(id);
        if (code == null) { return false; }
        transactions.executeWithoutResult(tx -> {
            int changed = jdbc.update("update notifications set status='SKIPPED',skip_reason=?,last_error=? "
                    + "where id=? and event=? and status='SENDING' and delivery_claim_id=?",
                    code, code, id, LlmUsageAlertAccess.EVENT, owner);
            if (changed > 0) { journal.finishNotificationAttempt(List.of(id), owner, "SKIPPED", code, null); }
        });
        return true;
    }
}
