package kr.ac.pusan.pickle.auth;

import java.sql.Timestamp;
import java.time.Instant;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Weekly retention sweep (Mon 04:40 KST) of spent auth artifacts:
 * {@code refresh_tokens} past their expiry (unusable — the reuse-detection
 * window has closed), {@code email_verifications} that were used or expired, and
 * {@code mfa_login_tokens} that were consumed or expired (single-use step-up
 * tokens with no post-expiry value), and {@code auth_reverifications} past
 * their 10-minute expiry (sudo-mode tokens, v0.24.0). Each is batched in a
 * bounded LIMIT loop to avoid long locks. Touches ONLY these four tables —
 * {@code audit_logs}/{@code vm_events} are permanent and must never be swept.
 */
@Component
public class AuthTokenRetentionSweeper {

    static final String JOB_ID = "auth-token-retention-sweeper";
    private static final int BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(AuthTokenRetentionSweeper.class);

    private final JdbcTemplate jdbcTemplate;

    public AuthTokenRetentionSweeper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One sweep. Public and argument-free for JobRunr; tests call it directly. */
    @Recurring(id = JOB_ID, cron = "40 4 * * 1", zoneId = "Asia/Seoul")
    @Job(name = JOB_ID, retries = 0)
    public void sweep() {
        Timestamp now = Timestamp.from(Instant.now());
        // Past-expiry refresh tokens: expired ones (and any revoked token whose
        // expiry has passed) can no longer be used or detected for reuse.
        int tokens = deleteBatched("""
                delete from refresh_tokens
                 where id in (select id from refresh_tokens
                               where expires_at < ? order by id limit ?)
                """, now);
        // Consumed or expired email verifications.
        int verifications = deleteBatched("""
                delete from email_verifications
                 where id in (select id from email_verifications
                               where used_at is not null or expires_at < ? order by id limit ?)
                """, now);
        // Consumed or expired single-use MFA login step-up tokens.
        int mfaLoginTokens = deleteBatched("""
                delete from mfa_login_tokens
                 where id in (select id from mfa_login_tokens
                               where consumed_at is not null or expires_at < ? order by id limit ?)
                """, now);
        // Expired sudo-mode reauthentication tokens (multi-use until expiry).
        int reverifications = deleteBatched("""
                delete from auth_reverifications
                 where id in (select id from auth_reverifications
                               where expires_at < ? order by id limit ?)
                """, now);
        log.info("auth-token retention sweep deleted {} refresh token(s), {} email verification(s), "
                + "{} mfa login token(s), {} reverification(s)",
                tokens, verifications, mfaLoginTokens, reverifications);
    }

    private int deleteBatched(String sql, Timestamp bound) {
        int deleted = 0;
        int affected;
        do {
            affected = jdbcTemplate.update(sql, bound, BATCH_SIZE);
            deleted += affected;
        } while (affected == BATCH_SIZE);
        return deleted;
    }
}
