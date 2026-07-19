package kr.ac.pusan.pickle.terminal;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.config.TerminalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Boot-time single-instance assertion for the web-terminal in-memory state
 * (docs/plan/05 D7 I2, docs/architecture.md). {@link TicketRegistry} and
 * {@link TerminalSessionRegistry} are process-local; if two api instances ran,
 * cap accounting and the admin session view would each see only half the world.
 *
 * <p>The assertion is a <b>PostgreSQL session-level advisory lock</b> held for the
 * process lifetime on a <b>dedicated connection</b> this guard keeps open (never
 * returned to the pool). A second api process gets a different DB session and its
 * {@code pg_try_advisory_lock} returns false, so it fails fast. Holding the lease
 * matters: a session-level advisory lock is released when its connection closes,
 * so taking it via a pooled {@code JdbcTemplate} call — which returns the
 * connection to the pool — would let Hikari's {@code maxLifetime} silently retire
 * that connection and drop the lock minutes after boot. This guard therefore owns
 * the connection outright and releases the lock + connection only at shutdown.</p>
 *
 * <p>Disabled via {@code pickle.terminal.enforce-single-instance} in tests, where
 * many Spring contexts are cached alive in one JVM and would trip on the shared
 * embedded database.</p>
 */
@Component
public class TerminalSingleInstanceGuard implements DisposableBean {

    /** Arbitrary stable key namespacing this lock (ASCII "PKTM" ~ pickle terminal). */
    static final long ADVISORY_LOCK_KEY = 0x50_4B_54_4DL;

    private static final Logger log = LoggerFactory.getLogger(TerminalSingleInstanceGuard.class);

    private final DataSource dataSource;
    private final boolean enforce;

    /** The dedicated connection holding the advisory lock; null when not enforced. */
    private Connection lockConnection;

    public TerminalSingleInstanceGuard(DataSource dataSource, TerminalProperties properties) {
        this.dataSource = dataSource;
        this.enforce = properties.enforceSingleInstance();
    }

    @PostConstruct
    void assertSingleInstance() throws SQLException {
        if (!enforce) {
            return;
        }
        Connection connection = dataSource.getConnection();
        boolean acquired;
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                acquired = rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            closeQuietly(connection);
            throw e;
        }
        if (!acquired) {
            closeQuietly(connection);
            throw new IllegalStateException("another pickle-api instance already holds the web "
                    + "terminal single-instance advisory lock. The in-memory session mirror and "
                    + "cap accounting require exactly one api instance (docs/architecture.md, "
                    + "plan/05 I2). Run a single instance or disable "
                    + "pickle.terminal.enforce-single-instance.");
        }
        // Keep the connection (and thus the session-level lock) for the process
        // lifetime — do NOT return it to the pool.
        this.lockConnection = connection;
        log.info("web-terminal single-instance advisory lock acquired on a dedicated connection");
    }

    @Override
    public void destroy() {
        if (lockConnection == null) {
            return;
        }
        try (Connection connection = lockConnection) {
            try (PreparedStatement statement =
                    connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setLong(1, ADVISORY_LOCK_KEY);
                statement.execute();
            }
        } catch (SQLException e) {
            log.warn("failed to release web-terminal single-instance advisory lock: {}",
                    e.getMessage());
        } finally {
            lockConnection = null;
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
