package kr.ac.pusan.pickle.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.auth.AuthTokenRetentionSweeper;
import kr.ac.pusan.pickle.notification.NotificationRetentionSweeper;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Retention sweepers: old notifications and spent auth tokens are batch
 * deleted while fresh rows survive — and the permanent {@code audit_logs}
 * table is never touched.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class RetentionSweeperTest {

    @Autowired
    private NotificationRetentionSweeper notificationSweeper;
    @Autowired
    private AuthTokenRetentionSweeper authTokenSweeper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        User user = userRepository.findByEmail("retention.user@pusan.ac.kr").orElseGet(() -> {
            User u = new User("retention.user@pusan.ac.kr", "{test-no-login}", "보존테스트");
            u.setStatus(UserStatus.ACTIVE);
            u.setEmailVerifiedAt(Instant.now());
            return userRepository.save(u);
        });
        userId = user.getId();
    }

    @Test
    void notificationSweepDeletesOldRowsKeepsRecentAndLeavesAuditLogs() {
        long oldId = insertNotification("400 days");
        long recentId = insertNotification("2 days");
        long auditBefore = auditCount();
        jdbcTemplate.update("""
                insert into audit_logs (actor_id, actor_role, action, created_at)
                values (?, 'USER', 'test.retention', now() - interval '400 days')
                """, userId);
        long auditWithMarker = auditCount();

        notificationSweeper.sweep();

        assertThat(exists("notifications", oldId)).isFalse();
        assertThat(exists("notifications", recentId)).isTrue();
        // audit_logs is permanent — the 400-day-old marker is untouched
        assertThat(auditCount()).isEqualTo(auditWithMarker).isGreaterThan(auditBefore);
    }

    @Test
    void authTokenSweepDeletesExpiredAndUsedKeepsLive() {
        long expiredToken = insertRefreshToken("- interval '1 day'");
        long liveToken = insertRefreshToken("+ interval '10 days'");
        long usedVerification = insertVerification("+ interval '1 day'", true);
        long expiredVerification = insertVerification("- interval '1 day'", false);
        long liveVerification = insertVerification("+ interval '1 day'", false);
        long consumedMfaToken = insertMfaLoginToken("+ interval '1 day'", true);
        long expiredMfaToken = insertMfaLoginToken("- interval '1 day'", false);
        long liveMfaToken = insertMfaLoginToken("+ interval '1 day'", false);

        authTokenSweeper.sweep();

        assertThat(exists("refresh_tokens", expiredToken)).isFalse();
        assertThat(exists("refresh_tokens", liveToken)).isTrue();
        assertThat(exists("email_verifications", usedVerification)).isFalse();
        assertThat(exists("email_verifications", expiredVerification)).isFalse();
        assertThat(exists("email_verifications", liveVerification)).isTrue();
        // consumed or expired step-up tokens go; a live unused one survives
        assertThat(exists("mfa_login_tokens", consumedMfaToken)).isFalse();
        assertThat(exists("mfa_login_tokens", expiredMfaToken)).isFalse();
        assertThat(exists("mfa_login_tokens", liveMfaToken)).isTrue();
    }

    private long insertNotification(String age) {
        return jdbcTemplate.queryForObject("""
                insert into notifications (user_id, event, title, body, status, created_at)
                values (?, 'test.retention', '보존', '본문', 'SENT', now() - interval '%s')
                returning id
                """.formatted(age), Long.class, userId);
    }

    private long insertRefreshToken(String expiryExpr) {
        return jdbcTemplate.queryForObject("""
                insert into refresh_tokens (user_id, token_hash, expires_at)
                values (?, ?, now() %s) returning id
                """.formatted(expiryExpr), Long.class, userId, UUID.randomUUID().toString());
    }

    private long insertVerification(String expiryExpr, boolean used) {
        return jdbcTemplate.queryForObject("""
                insert into email_verifications (user_id, token_hash, purpose, expires_at, used_at)
                values (?, ?, 'SIGNUP', now() %s, %s) returning id
                """.formatted(expiryExpr, used ? "now()" : "null"),
                Long.class, userId, UUID.randomUUID().toString());
    }

    private long insertMfaLoginToken(String expiryExpr, boolean consumed) {
        return jdbcTemplate.queryForObject("""
                insert into mfa_login_tokens (user_id, token_hash, expires_at, consumed_at)
                values (?, ?, now() %s, %s) returning id
                """.formatted(expiryExpr, consumed ? "now()" : "null"),
                Long.class, userId, UUID.randomUUID().toString());
    }

    private boolean exists(String table, long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists(select 1 from " + table + " where id = ?)", Boolean.class, id));
    }

    private long auditCount() {
        return jdbcTemplate.queryForObject("select count(*) from audit_logs", Long.class);
    }
}
