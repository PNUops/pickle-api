package kr.ac.pusan.pickle.networkpolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
