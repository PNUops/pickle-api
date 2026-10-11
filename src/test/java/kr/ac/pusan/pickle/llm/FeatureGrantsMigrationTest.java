package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V139 against rows that existed before it, which no other test can see: every
 * Spring test starts from an empty database migrated to the latest version, so
 * the data the migration transforms is never there.
 *
 * <p>The database is taken to V138, given one request in each state that
 * matters and a key, and then taken to V139.
 */
class FeatureGrantsMigrationTest {

    @Test
    void chatReachesEveryKeyEveryApprovedGrantAndEveryRequest() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            migrate(dataSource, "138");

            long orgId = jdbc.queryForObject(
                    "insert into orgs (name) values ('이관 기관') returning id", Long.class);
            long userId = jdbc.queryForObject("""
                    insert into users (email, name, role, status, password_hash)
                    values ('migration@pusan.ac.kr', '신청자', 'USER', 'ACTIVE', 'x') returning id
                    """, Long.class);
            long workspaceId = jdbc.queryForObject(
                    "insert into workspaces (kind, name) values ('PROJECT', '이관') returning id",
                    Long.class);

            long pending = request(jdbc, workspaceId, orgId, userId, "대기");
            long approved = request(jdbc, workspaceId, orgId, userId, "승인");
            long approvedWithImages = request(jdbc, workspaceId, orgId, userId, "이미지 승인");
            jdbc.update("""
                    update llm_key_request_details
                       set granted_passthrough_endpoints = '["images"]'
                     where request_id = ?
                    """, approvedWithImages);
            approve(jdbc, approved, userId);
            approve(jdbc, approvedWithImages, userId);
            // Approved before V100 tightened the account rule: an amount with
            // no account. The rule's trigger re-checks the whole row on any
            // update, so this row would stop V139 at commit unless the
            // migration holds the trigger off for its own update.
            long legacy = request(jdbc, workspaceId, orgId, userId, "이전 승인");
            jdbc.update("alter table llm_key_request_details "
                    + "disable trigger trg_llm_request_openrouter_account");
            jdbc.update("update llm_key_request_details set granted_credit_limit = 5 "
                    + "where request_id = ?", legacy);
            jdbc.update("alter table llm_key_request_details "
                    + "enable trigger trg_llm_request_openrouter_account");
            approve(jdbc, legacy, userId);

            long emptyKey = key(jdbc, workspaceId, orgId, approved, userId, "[]");
            long imagesKey = key(jdbc, workspaceId, orgId, approvedWithImages, userId,
                    "[\"images\"]");
            jdbc.update("insert into llm_gateway_state (id) values (true)");
            long generation = jdbc.queryForObject(
                    "select generation from llm_gateway_state", Long.class);

            migrate(dataSource, "139");

            // Every key, so enforcing chat takes it from nobody. Chat is added,
            // not substituted: a granted capability stays.
            assertThat(list(jdbc, "llm_api_keys", "passthrough_endpoints", "id", emptyKey))
                    .isEqualTo("[\"chat\"]");
            assertThat(list(jdbc, "llm_api_keys", "passthrough_endpoints", "id", imagesKey))
                    .isEqualTo("[\"images\", \"chat\"]");

            // Every approved grant, because a recipient issued later or retried
            // after a failure gets a key from it.
            assertThat(detail(jdbc, "granted_passthrough_endpoints", approved))
                    .isEqualTo("[\"chat\"]");
            assertThat(detail(jdbc, "granted_passthrough_endpoints", approvedWithImages))
                    .isEqualTo("[\"images\", \"chat\"]");
            assertThat(detail(jdbc, "granted_passthrough_endpoints", legacy))
                    .isEqualTo("[\"chat\"]");
            // The trigger is back on once the migration is done.
            assertThat(jdbc.queryForObject("""
                    select tgenabled::text from pg_trigger
                     where tgname = 'trg_llm_request_openrouter_account'
                    """, String.class)).isEqualTo("O");
            // A pending grant is overwritten at approval, so it is left alone.
            assertThat(detail(jdbc, "granted_passthrough_endpoints", pending)).isEqualTo("[]");

            // Every request's own choice, whatever its status.
            assertThat(detail(jdbc, "req_passthrough_endpoints", pending)).isEqualTo("[\"chat\"]");
            assertThat(detail(jdbc, "req_passthrough_endpoints", approved))
                    .isEqualTo("[\"chat\"]");

            // The fill value does not stay behind as a default: a row that
            // names no choice is refused rather than given one.
            long later = jdbc.queryForObject("""
                    insert into requests (resource_type, workspace_id, org_id, requester_id,
                                          purpose, display_name)
                    values ('LLM_API_KEY', ?, ?, ?, '나중', '나중') returning id
                    """, Long.class, workspaceId, orgId, userId);
            assertThatThrownBy(() -> jdbc.update(
                    "insert into llm_key_request_details (request_id) values (?)", later))
                    .isInstanceOf(DataAccessException.class);

            // The vocabulary is twelve names; anything else still fails.
            jdbc.update("update llm_api_keys set passthrough_endpoints = ? ::jsonb where id = ?",
                    "[\"chat\", \"web_search\", \"pdf_ocr\", \"containers\", \"images\", "
                            + "\"embeddings\", \"rerank\", \"speech\", \"transcriptions\", "
                            + "\"videos\", \"batches\", \"files\"]", emptyKey);
            assertThatThrownBy(() -> jdbc.update(
                    "update llm_api_keys set passthrough_endpoints = '[\"image\"]' where id = ?",
                    emptyKey)).isInstanceOf(DataAccessException.class);

            // The gateway sees the new lists on its next poll.
            assertThat(jdbc.queryForObject("select generation from llm_gateway_state",
                    Long.class)).isEqualTo(generation + 1);
        }
    }

    /**
     * On a database that has never served a gateway there is no generation row,
     * and the migration must not create one: migrations carry no rows.
     */
    @Test
    void theGenerationRowIsNotCreatedWhereThereWasNone() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            migrate(dataSource, "139");
            assertThat(new JdbcTemplate(dataSource).queryForObject(
                    "select count(*) from llm_gateway_state", Long.class)).isZero();
        }
    }

    private static void migrate(DataSource dataSource, String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).load().migrate();
    }

    private static long request(JdbcTemplate jdbc, long workspaceId, long orgId, long userId,
            String name) {
        long requestId = jdbc.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '이관 시험', ?) returning id
                """, Long.class, workspaceId, orgId, userId, name);
        jdbc.update("insert into llm_key_request_details (request_id) values (?)", requestId);
        return requestId;
    }

    private static void approve(JdbcTemplate jdbc, long requestId, long reviewerId) {
        jdbc.update("""
                insert into request_reviews (request_id, reviewer_id, decision)
                values (?, ?, 'APPROVE'::review_decision)
                """, requestId, reviewerId);
        jdbc.update("update requests set status = 'APPROVED'::request_status where id = ?",
                requestId);
    }

    private static long key(JdbcTemplate jdbc, long workspaceId, long orgId, long requestId,
            long userId, String endpoints) {
        return jdbc.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, purpose,
                                          status, passthrough_endpoints, created_by)
                values (?, ?, ?, '이관 키', '이관 시험', 'PENDING'::llm_api_key_status,
                        ?::jsonb, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, endpoints, userId);
    }

    private static String list(JdbcTemplate jdbc, String table, String column, String idColumn,
            long id) {
        return jdbc.queryForObject("select " + column + "::text from " + table + " where "
                + idColumn + " = ?", String.class, id);
    }

    private static String detail(JdbcTemplate jdbc, String column, long requestId) {
        return list(jdbc, "llm_key_request_details", column, "request_id", requestId);
    }
}
