package kr.ac.pusan.pickle.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database's own guard on an approved request: it must carry what the
 * reviewer granted.
 *
 * <p>The invariant was lost once before and the loss was invisible — an
 * approved request whose specification was never written simply sat there,
 * looking decided. The check that restored it named the VM explicitly, so
 * every later type would have inherited that same silence. These tests are
 * about the generalization: each type is checked, and a type nobody has taught
 * it about is refused rather than waved through.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ApprovedRequestGrantInvariantTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A submitted request of one type, returning its id. */
    private long submitRequest(String resourceType) {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        Long userId = jdbcTemplate.queryForObject(
                "select id from users where email = ?", Long.class, SeedFixtures.SYSADMIN_EMAIL);
        Long workspaceId = jdbcTemplate.queryForObject(
                "select id from workspaces order by id limit 1", Long.class);
        Long id = jdbcTemplate.queryForObject("""
                insert into requests (workspace_id, org_id, requester_id, resource_type,
                                      purpose, display_name, status)
                values (?, ?, ?, ?::resource_type, '테스트', '테스트 리소스', 'SUBMITTED')
                returning id
                """, Long.class, workspaceId, orgId, userId, resourceType);
        return id;
    }

    private void approve(long requestId) {
        Long reviewerId = jdbcTemplate.queryForObject(
                "select id from users where email = ?", Long.class, SeedFixtures.SYSADMIN_EMAIL);
        jdbcTemplate.update("""
                insert into request_reviews (request_id, reviewer_id, decision,
                                             granted_start_date, granted_end_date)
                values (?, ?, 'APPROVE', ?, ?)
                """, requestId, reviewerId, LocalDate.now(), LocalDate.now().plusDays(30));
        jdbcTemplate.update("update requests set status = 'APPROVED' where id = ?", requestId);
    }

    @Test
    void anApprovedKeyRequestWithoutItsDetailIsRefused() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            long requestId = submitRequest("LLM_API_KEY");
            approve(requestId);
        })).hasStackTraceContaining("no complete granted specification");
    }

    @Test
    void anApprovedKeyRequestWithItsDetailCommits() {
        Long committed = transactionTemplate.execute(status -> {
            long requestId = submitRequest("LLM_API_KEY");
            jdbcTemplate.update(
                    "insert into llm_key_request_details (request_id) values (?)", requestId);
            approve(requestId);
            return requestId;
        });
        assertThat(jdbcTemplate.queryForObject(
                "select status::text from requests where id = ?", String.class, committed))
                .isEqualTo("APPROVED");
    }

    @Test
    void theVmRuleStillHolds() {
        // The generalization must not have loosened the type it was written for.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            long requestId = submitRequest("VM");
            Long imageId = jdbcTemplate.queryForObject(
                    "select id from os_images order by id limit 1", Long.class);
            jdbcTemplate.update("""
                    insert into vm_request_details (request_id, image_id, req_vcpu,
                                                    req_memory_mb, req_disk_gb)
                    values (?, ?, 2, 2048, 20)
                    """, requestId, imageId);
            approve(requestId); // granted_* left null
        })).hasStackTraceContaining("no complete granted specification");
    }
}
