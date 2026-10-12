package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Nullable provenance preserves historical records and the previous insert shape. */
class DomainRequestAssociationMigrationTest {
    @Test
    void upgradingAnExistingSchemaPreservesUnknownOriginsAndEnforcesNewReferences() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("139").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            long org = jdbc.queryForObject("insert into orgs(name) values('Migration test organisation') returning id", Long.class);
            long workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT','Migration test workspace') returning id", Long.class);
            long legacy = insertWithoutRequest(jdbc, workspace, org, "legacy.example.invalid");

            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

            assertThat(jdbc.queryForObject("select request_id from domains where id=?", Long.class, legacy)).isNull();
            long previousInsert = insertWithoutRequest(jdbc, workspace, org, "previous-insert.example.invalid");
            assertThat(jdbc.queryForObject("select request_id from domains where id=?", Long.class, previousInsert)).isNull();
            assertThatThrownBy(() -> jdbc.update("update domains set request_id=9223372036854775807 where id=?", legacy))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("domains_request_fk");
            assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='140' and success", Long.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname='domains_request_id_idx'", Long.class))
                    .isEqualTo(1);
        }
    }

    private long insertWithoutRequest(JdbcTemplate jdbc, long workspace, long org, String fqdn) {
        return jdbc.queryForObject("""
                insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at)
                values(?,?,'EXTERNAL',?,'example.invalid','ACTIVE',now()+interval '30 days') returning id
                """, Long.class, workspace, org, fqdn);
    }
}
