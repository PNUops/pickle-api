package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VmNetworkPolicyAdvisoryLockRecoveryTest {

    @Test
    void uncertainOutcomeEvictsThePhysicalSessionBeforeReadOnlyReconnect() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            HikariConfig config = new HikariConfig();
            config.setDataSource(postgres.getPostgresDatabase());
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            try (HikariDataSource pool = new HikariDataSource(config)) {
                VmNetworkPolicyAdvisoryLock lock = new VmNetworkPolicyAdvisoryLock(pool);
                AtomicLong originalPid = new AtomicLong();
                assertThatThrownBy(() -> lock.runOnConnection(17, connection -> {
                    originalPid.set(backendPid(connection));
                    throw new VmNetworkPolicyAdvisoryLock.OutcomeUnknownException(
                            "Simulated lost commit response.", null);
                })).isInstanceOf(VmNetworkPolicyAdvisoryLock.OutcomeUnknownException.class);

                try (Connection after = pool.getConnection()) {
                    assertThat(backendPid(after)).isNotEqualTo(originalPid.get());
                    long key = 0x564d465700000000L ^ 17L;
                    try (var statement = after.prepareStatement("select pg_try_advisory_lock(?)")) {
                        statement.setLong(1, key);
                        try (ResultSet rows = statement.executeQuery()) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getBoolean(1)).isTrue();
                        }
                    }
                    try (var statement = after.prepareStatement("select pg_advisory_unlock(?)")) {
                        statement.setLong(1, key);
                        statement.execute();
                    }
                }
                AtomicLong lostPid = new AtomicLong();
                assertThatThrownBy(() -> lock.runOnConnection(18, connection -> {
                    lostPid.set(backendPid(connection));
                    throw new SQLException("Simulated lock-session loss.");
                })).isInstanceOf(VmNetworkPolicyAdvisoryLock.OutcomeUnknownException.class);
                try (Connection afterLoss = pool.getConnection()) {
                    assertThat(backendPid(afterLoss)).isNotEqualTo(lostPid.get());
                }
            }
        }
    }

    private static long backendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select pg_backend_pid()")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
