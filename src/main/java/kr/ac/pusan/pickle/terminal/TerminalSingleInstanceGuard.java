package kr.ac.pusan.pickle.terminal;

import jakarta.annotation.PostConstruct;
import kr.ac.pusan.pickle.config.TerminalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Boot-time single-instance assertion for the web-terminal in-memory state
 * (docs/plan/05 D7 I2, docs/architecture.md). {@link TicketRegistry} and
 * {@link TerminalSessionRegistry} are process-local; if two api instances ran,
 * cap accounting and the admin session view would each see only half the world.
 *
 * <p>The assertion is a <b>PostgreSQL session-level advisory lock</b> taken at
 * startup on a pooled connection and never released — a second api process gets
 * a different DB session and its {@code pg_try_advisory_lock} returns false, so
 * it fails fast. This is a genuine cross-process guard (a static JVM flag cannot
 * see another process). Disabled via {@code pickle.terminal.enforce-single-instance}
 * in tests, where many Spring contexts are cached alive in one JVM and would trip
 * on the shared embedded database.</p>
 */
@Component
public class TerminalSingleInstanceGuard {

    /** Arbitrary stable key namespacing this lock (ASCII "PKTM" ~ pickle terminal). */
    static final long ADVISORY_LOCK_KEY = 0x50_4B_54_4DL;

    private static final Logger log = LoggerFactory.getLogger(TerminalSingleInstanceGuard.class);

    private final JdbcTemplate jdbcTemplate;
    private final boolean enforce;

    public TerminalSingleInstanceGuard(JdbcTemplate jdbcTemplate, TerminalProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.enforce = properties.enforceSingleInstance();
    }

    @PostConstruct
    void assertSingleInstance() {
        if (!enforce) {
            return;
        }
        Boolean acquired = jdbcTemplate.queryForObject(
                "select pg_try_advisory_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("another pickle-api instance already holds the web "
                    + "terminal single-instance advisory lock. The in-memory session mirror and "
                    + "cap accounting require exactly one api instance (docs/architecture.md, "
                    + "plan/05 I2). Run a single instance or disable "
                    + "pickle.terminal.enforce-single-instance.");
        }
        log.info("web-terminal single-instance advisory lock acquired");
    }
}
