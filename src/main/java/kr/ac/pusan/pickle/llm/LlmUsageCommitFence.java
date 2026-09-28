package kr.ac.pusan.pickle.llm;

import java.sql.PreparedStatement;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Orders raw-event commits before a rollup's visible-ID snapshot. */
@Component
final class LlmUsageCommitFence {

    private static final long LOCK_KEY = 0x504b555345564e54L;
    private final JdbcTemplate jdbcTemplate;

    LlmUsageCommitFence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void lockInTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LLM usage commit fence requires a transaction");
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            }
            return null;
        });
    }
}
