package kr.ac.pusan.pickle.notification;

import kr.ac.pusan.pickle.notification.dto.NotificationPreferencesView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Personal usage-mail policy is independent of inbox and legacy delivery policy. */
@Service
public class NotificationPreferencesService {
    private final JdbcTemplate jdbc;

    public NotificationPreferencesService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public NotificationPreferencesView get(long userId) {
        return new NotificationPreferencesView(usageMailEnabled(userId));
    }

    public boolean usageMailEnabled(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select coalesce((select usage_mail_enabled from notification_preferences
                                  where user_id = ?), true)
                """, Boolean.class, userId));
    }

    @Transactional
    public NotificationPreferencesView update(long userId, boolean enabled) {
        jdbc.update("""
                insert into notification_preferences(user_id, usage_mail_enabled, updated_at)
                values (?, ?, now())
                on conflict(user_id) do update
                    set usage_mail_enabled = excluded.usage_mail_enabled, updated_at = now()
                """, userId, enabled);
        return new NotificationPreferencesView(enabled);
    }

}
