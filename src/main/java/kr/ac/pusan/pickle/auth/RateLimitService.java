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
 *       failures the (account, client address) pair is locked, doubling from 1
 *       minute up to 15 minutes. Password failures and second-factor code
 *       failures are counted, and locked, separately. Keying the lockout on the
 *       pair keeps it out of reach of anyone who knows only the address; the
 *       sliding window above is what bounds one account's total across many
 *       addresses.</li>
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
    /** Wrong password: locks the account out of logging in. */
    private static final String LOGIN_FAIL_SCOPE = "login_fail";
    /**
     * Wrong second-factor code on an account whose password did check out.
     * Deliberately a separate counter from {@link #LOGIN_FAIL_SCOPE}: mistyping
     * recovery codes is what someone who lost the authenticator device does, and
     * it must throttle further code attempts without locking that person out of
     * login as well.
     */
    private static final String CODE_FAIL_SCOPE = "code_fail";

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

    /**
     * Throws 429 when this account is under an escalating login lockout <em>from
     * this client address</em>. The lock is keyed on the pair rather than the
     * account alone because this check runs before the password is verified:
     * an account-wide key would let anyone who knows an address lock its owner
     * out remotely, so a caller can only ever lock the pair it is calling from.
     * Total attempts against one account across many addresses stay bounded by
     * the separate account-wide sliding window ({@code hit("login:acct", …)}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void checkLoginLock(String account, String ip) {
        checkLock(LOGIN_FAIL_SCOPE, lockSubject(account, ip));
    }

    /**
     * Throws 429 when this account is under a second-factor code lockout from
     * this client address. Keyed on the pair for the same reason as
     * {@link #checkLoginLock}: one address must not be able to lock an account's
     * code entry for everyone else. The account-wide sliding window
     * ({@code hit("login:acct", …)} and its per-endpoint siblings) remains the
     * cap on attempts spread across many addresses.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void checkCodeLock(String account, String ip) {
        checkLock(CODE_FAIL_SCOPE, lockSubject(account, ip));
    }

    /**
     * Registers a failed password check for this (account, client address) pair;
     * from the {@value #LOCKOUT_THRESHOLD}th consecutive failure the lockout
     * doubles (1 min → … → 15 min cap). The address is part of the key so a
     * remote attacker's failures escalate only against their own address and
     * never lock the legitimate owner out. Attempts distributed across many
     * addresses are held down instead by the account-wide sliding window
     * ({@code hit("login:acct", …)}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerLoginFailure(String account, String ip) {
        registerFailure(LOGIN_FAIL_SCOPE, lockSubject(account, ip));
    }

    /**
     * Same escalation as {@link #registerLoginFailure}, on the code counter and
     * keyed on the same (account, client address) pair, so a wrong code from one
     * address cannot block the account's owner from entering theirs. The
     * account-wide sliding window keeps the total across addresses bounded.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerCodeFailure(String account, String ip) {
        registerFailure(CODE_FAIL_SCOPE, lockSubject(account, ip));
    }

    /**
     * A successful login resets the consecutive-failure counter for this
     * (account, client address) pair — the same key the failures were recorded
     * under, so proving the password clears only the caller's own lockout and
     * cannot wipe another address's escalation. Account-wide volume is still
     * accounted for by the sliding window ({@code hit("login:acct", …)}), which
     * a success does not reset.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearLoginFailures(String account, String ip) {
        clearFailures(LOGIN_FAIL_SCOPE, lockSubject(account, ip));
    }

    /**
     * An accepted second-factor code resets the code-failure counter for this
     * (account, client address) pair only, mirroring how the failures were keyed
     * so one client's success cannot clear another's escalation. The
     * account-wide sliding window remains the cap on attempts spread across
     * addresses.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearCodeFailures(String account, String ip) {
        clearFailures(CODE_FAIL_SCOPE, lockSubject(account, ip));
    }

    /**
     * Composite lockout key. A missing or blank client address collapses to a
     * single placeholder rather than to the account alone, so an unknown address
     * can never share a key with — or widen the blast radius of — a known one.
     */
    private static String lockSubject(String account, String ip) {
        String client = (ip == null || ip.isBlank()) ? "-" : ip;
        return account + "|" + client;
    }

    private void checkLock(String scope, String account) {
        OffsetDateTime lockedUntil = jdbcTemplate.query("""
                select locked_until from auth_rate_limits
                 where scope = ? and subject = ? and window_start = timestamptz 'epoch'
                """, rs -> rs.next() ? rs.getObject("locked_until", OffsetDateTime.class) : null,
                scope, account);
        if (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) {
            long retryAfter = Math.max(1,
                    Duration.between(Instant.now(), lockedUntil.toInstant()).toSeconds());
            throw ApiException.rateLimited(retryAfter);
        }
    }

    private void registerFailure(String scope, String account) {
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
                """.formatted(LOCKOUT_THRESHOLD, LOCKOUT_THRESHOLD), scope, account);
    }

    private void clearFailures(String scope, String account) {
        jdbcTemplate.update("""
                delete from auth_rate_limits
                 where scope = ? and subject = ? and window_start = timestamptz 'epoch'
                """, scope, account);
    }
}
