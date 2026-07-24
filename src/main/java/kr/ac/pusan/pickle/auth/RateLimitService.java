package kr.ac.pusan.pickle.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import kr.ac.pusan.pickle.common.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL counter-table rate limiting (no Redis):
 *
 * <ul>
 *   <li>Sliding window: 15-second buckets in {@code auth_rate_limits}; a
 *       request is rejected once the sum over the last 60 seconds exceeds the
 *       endpoint limit (default 10/min per IP and per account).</li>
 *   <li>Escalating lockout: after {@value #LOCKOUT_THRESHOLD} consecutive
 *       login failures the account is locked, doubling from 1 minute up to
 *       15 minutes.</li>
 * </ul>
 *
 * Methods run in their own transaction so counters survive the business
 * rollback that follows a rejected request.
 */
@Service
public class RateLimitService {

    public static final int DEFAULT_LIMIT_PER_MINUTE = 10;
    static final int LOCKOUT_THRESHOLD = 5;

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Duration BUCKET = Duration.ofSeconds(15);
    private static final String LOGIN_FAIL_SCOPE = "login_fail";

    private record WindowState(long total, OffsetDateTime oldest) {
    }

    private final JdbcTemplate jdbcTemplate;

    public RateLimitService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Counts this request and throws 429 RATE_LIMITED (with Retry-After) when
     * the sliding-window total exceeds {@code limitPerMinute}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void hit(String scope, String subject, int limitPerMinute) {
        jdbcTemplate.update("""
                delete from auth_rate_limits
                 where scope = ? and subject = ? and window_start <= now() - interval '120 seconds'
                """, scope, subject);
        jdbcTemplate.update("""
                insert into auth_rate_limits (scope, subject, window_start, request_count)
                values (?, ?, date_bin(interval '15 seconds', now(), timestamptz 'epoch'), 1)
                on conflict (scope, subject, window_start)
                do update set request_count = auth_rate_limits.request_count + 1, updated_at = now()
                """, scope, subject);
        WindowState window = jdbcTemplate.queryForObject("""
                select coalesce(sum(request_count), 0) as total, min(window_start) as oldest
                  from auth_rate_limits
                 where scope = ? and subject = ? and window_start > now() - interval '60 seconds'
                """,
                (rs, rowNum) -> new WindowState(rs.getLong("total"),
                        rs.getObject("oldest", OffsetDateTime.class)),
                scope, subject);
        if (window.total() > limitPerMinute) {
            Instant oldest = window.oldest().toInstant();
            long retryAfter = Math.max(1,
                    Duration.between(Instant.now(), oldest.plus(WINDOW).plus(BUCKET)).toSeconds());
            throw ApiException.rateLimited(retryAfter);
        }
    }

    /**
     * Sliding 1-hour window (15-minute buckets) over the same counter table —
     * used by low-frequency admin actions (announcements: 10/hour/author).
     * Counts this request and throws 429 RATE_LIMITED with Retry-After when
     * the window total exceeds {@code limitPerHour}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void hitHourly(String scope, String subject, int limitPerHour) {
        jdbcTemplate.update("""
                delete from auth_rate_limits
                 where scope = ? and subject = ? and window_start <= now() - interval '120 minutes'
                """, scope, subject);
        jdbcTemplate.update("""
                insert into auth_rate_limits (scope, subject, window_start, request_count)
                values (?, ?, date_bin(interval '15 minutes', now(), timestamptz 'epoch'), 1)
                on conflict (scope, subject, window_start)
                do update set request_count = auth_rate_limits.request_count + 1, updated_at = now()
                """, scope, subject);
        WindowState window = jdbcTemplate.queryForObject("""
                select coalesce(sum(request_count), 0) as total, min(window_start) as oldest
                  from auth_rate_limits
                 where scope = ? and subject = ? and window_start > now() - interval '60 minutes'
                """,
                (rs, rowNum) -> new WindowState(rs.getLong("total"),
                        rs.getObject("oldest", OffsetDateTime.class)),
                scope, subject);
        if (window.total() > limitPerHour) {
            Instant oldest = window.oldest().toInstant();
            // the oldest bucket ages out of the window at oldest + 60m + 15m
            long retryAfter = Math.max(1, Duration.between(Instant.now(),
                    oldest.plus(Duration.ofMinutes(75))).toSeconds());
            throw ApiException.rateLimited(retryAfter);
        }
    }

    /** Throws 429 when the account is under an escalating login lockout. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void checkLoginLock(String account) {
        OffsetDateTime lockedUntil = jdbcTemplate.query("""
                select locked_until from auth_rate_limits
                 where scope = ? and subject = ? and window_start = timestamptz 'epoch'
                """, rs -> rs.next() ? rs.getObject("locked_until", OffsetDateTime.class) : null,
                LOGIN_FAIL_SCOPE, account);
        if (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) {
            long retryAfter = Math.max(1,
                    Duration.between(Instant.now(), lockedUntil.toInstant()).toSeconds());
            throw ApiException.rateLimited(retryAfter);
        }
    }

    /**
     * Registers a failed credential check; from the {@value #LOCKOUT_THRESHOLD}th
     * consecutive failure the lockout doubles (1 min → … → 15 min cap).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerLoginFailure(String account) {
        jdbcTemplate.update("""
                insert into auth_rate_limits (scope, subject, window_start, request_count)
                values (?, ?, timestamptz 'epoch', 1)
                on conflict (scope, subject, window_start)
                do update set
                    request_count = auth_rate_limits.request_count + 1,
                    locked_until = case
                        when auth_rate_limits.request_count + 1 >= %d
                        then now() + interval '1 minute' * least(
                                power(2, least(auth_rate_limits.request_count + 1 - %d, 4)), 15)
                        else null end,
                    updated_at = now()
                """.formatted(LOCKOUT_THRESHOLD, LOCKOUT_THRESHOLD), LOGIN_FAIL_SCOPE, account);
    }

    /** Successful login resets the consecutive-failure counter. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearLoginFailures(String account) {
        jdbcTemplate.update("""
                delete from auth_rate_limits
                 where scope = ? and subject = ? and window_start = timestamptz 'epoch'
                """, LOGIN_FAIL_SCOPE, account);
    }
}
