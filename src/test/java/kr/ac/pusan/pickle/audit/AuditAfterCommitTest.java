package kr.ac.pusan.pickle.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Success audits must not survive a business-transaction rollback.
 * {@link AuditService#recordAfterCommit} defers the REQUIRES_NEW write to
 * afterCommit — a rolled-back business tx leaves no false success row, a
 * committed one records exactly one, and outside any transaction it writes
 * immediately (unlike {@link AuditService#record}, which is REQUIRES_NEW and
 * would commit the audit even when the outer tx rolls back).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AuditAfterCommitTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void recordsOnceWhenTheBusinessTransactionCommits() {
        String action = uniqueAction();
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                auditService.recordAfterCommit(1L, "SYS_ADMIN", action, "test", 1L,
                        Map.of("k", "v"), "127.0.0.1"));
        assertThat(countByAction(action)).isEqualTo(1);
    }

    @Test
    void recordsNothingWhenTheBusinessTransactionRollsBack() {
        String action = uniqueAction();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            auditService.recordAfterCommit(1L, "SYS_ADMIN", action, "test", 1L,
                    Map.of("k", "v"), "127.0.0.1");
            status.setRollbackOnly();
        });
        assertThat(countByAction(action)).isZero();
    }

    @Test
    void recordsImmediatelyWithNoActiveTransaction() {
        String action = uniqueAction();
        auditService.recordAfterCommit(1L, "SYS_ADMIN", action, "test", 1L,
                Map.of("k", "v"), "127.0.0.1");
        assertThat(countByAction(action)).isEqualTo(1);
    }

    private static String uniqueAction() {
        return "test.after_commit." + UUID.randomUUID();
    }

    private long countByAction(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?",
                Long.class, action);
    }
}
