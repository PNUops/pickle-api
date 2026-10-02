package kr.ac.pusan.pickle.orgs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes short roster mutations and recipient selection, never SMTP. */
@Component
public class OrgAdministrationLock {
    private final JdbcTemplate jdbc;

    public OrgAdministrationLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Organisation administration requires a transaction");
        }
        jdbc.queryForObject("select pg_advisory_xact_lock(1129074511, 1)::text", String.class);
    }
}
