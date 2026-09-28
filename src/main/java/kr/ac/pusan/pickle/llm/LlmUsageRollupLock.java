package kr.ac.pusan.pickle.llm;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

/** Serializes rollup rebuilds and raw retention on one database session key. */
@Component
final class LlmUsageRollupLock {

    private static final long LOCK_KEY = 0x50_4B_55_52L;
    private final DataSource dataSource;

    LlmUsageRollupLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    <T> Optional<T> run(Supplier<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection)) {
                return Optional.empty();
            }
            try {
                return Optional.of(action.get());
            } finally {
                unlock(connection);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("LLM usage rollup/retention lock is unavailable", failure);
        }
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getBoolean(1)) {
                    return;
                }
            }
            throw new SQLException("LLM usage lock was not held at release");
        } catch (SQLException failure) {
            // Returning a still-locked physical connection to the pool would
            // make future sweeps look permanently contended.
            if (dataSource instanceof HikariDataSource hikari) {
                hikari.evictConnection(connection);
            } else {
                connection.abort(Runnable::run);
            }
            throw failure;
        }
    }
}
