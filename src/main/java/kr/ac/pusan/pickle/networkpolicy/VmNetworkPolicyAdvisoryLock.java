package kr.ac.pusan.pickle.networkpolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Per-VM session advisory lock held on a dedicated connection across PVE calls. */
@Component
public class VmNetworkPolicyAdvisoryLock {

    private static final long NAMESPACE = 0x564d465700000000L;
    private final DataSource dataSource;

    public VmNetworkPolicyAdvisoryLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean run(long vmId, Runnable action) {
        long key = NAMESPACE ^ vmId;
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, key)) {
                return false;
            }
            RuntimeException actionFailure = null;
            try {
                action.run();
                return true;
            } catch (RuntimeException failure) {
                actionFailure = failure;
                throw failure;
            } finally {
                try {
                    unlock(connection, key);
                } catch (RuntimeException unlockFailure) {
                    if (actionFailure == null) {
                        throw unlockFailure;
                    }
                    actionFailure.addSuppressed(unlockFailure);
                }
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("VM 방화벽 single-writer lock을 확인할 수 없습니다.", failure);
        }
    }

    /** Recovery callback uses this same session for every protected SQL statement. */
    public <T> T runOnConnection(long vmId, ConnectionAction<T> action) {
        long key = NAMESPACE ^ vmId;
        try (Connection connection = dataSource.getConnection()) {
            boolean acquired;
            try {
                if (!connection.getAutoCommit()) {
                    throw new IllegalStateException("VM policy lock session is not in autocommit mode.");
                }
                acquired = tryLock(connection, key);
            } catch (java.sql.SQLException uncertain) {
                discard(connection, uncertain);
                throw new OutcomeUnknownException("Recovery lock acquisition is uncertain.",
                        uncertain);
            }
            if (!acquired) {
                throw new IllegalStateException("VM policy lock is unavailable.");
            }
            T result;
            try {
                result = action.run(connection);
            } catch (OutcomeUnknownException uncertain) {
                discard(connection, uncertain);
                throw uncertain;
            } catch (java.sql.SQLException uncertain) {
                discard(connection, uncertain);
                throw new OutcomeUnknownException("Recovery lock connection is uncertain.", uncertain);
            } catch (RuntimeException failure) {
                try {
                    unlock(connection, key);
                } catch (RuntimeException unlockFailure) {
                    discard(connection, unlockFailure);
                    throw new OutcomeUnknownException("Recovery lock release is uncertain.",
                            unlockFailure);
                }
                throw failure;
            } catch (Error failure) {
                discard(connection, failure);
                throw failure;
            }
            try {
                unlock(connection, key);
            } catch (RuntimeException unlockFailure) {
                discard(connection, unlockFailure);
                throw new OutcomeUnknownException("Recovery lock release is uncertain.",
                        unlockFailure);
            }
            return result;
        } catch (java.sql.SQLException failure) {
            throw new OutcomeUnknownException("Recovery lock connection is uncertain.", failure);
        }
    }

    private void discard(Connection connection, Throwable failure) {
        try {
            if (dataSource instanceof HikariDataSource hikari) {
                hikari.evictConnection(connection);
            } else {
                connection.abort(Runnable::run);
            }
        } catch (RuntimeException | java.sql.SQLException evictionFailure) {
            failure.addSuppressed(evictionFailure);
            try {
                connection.abort(Runnable::run);
            } catch (RuntimeException | java.sql.SQLException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
        }
    }

    @FunctionalInterface
    public interface ConnectionAction<T> {
        T run(Connection connection) throws java.sql.SQLException;
    }

    public static final class OutcomeUnknownException extends IllegalStateException {
        public OutcomeUnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static boolean tryLock(Connection connection, long key) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_try_advisory_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, long key) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("VM 방화벽 single-writer lock을 해제하지 못했습니다.", failure);
        }
    }
}
