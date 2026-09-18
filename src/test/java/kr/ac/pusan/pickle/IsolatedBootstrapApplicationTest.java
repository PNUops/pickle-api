package kr.ac.pusan.pickle;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Boots the real non-web one-shot against a migrated empty PostgreSQL database. */
class IsolatedBootstrapApplicationTest {

    @Test
    void migratesSeedsExactAdminAndExitsWithoutDevFixtures() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            String url;
            try (Connection connection = postgres.getPostgresDatabase().getConnection()) {
                url = connection.getMetaData().getURL();
            }
            SpringApplication application = new SpringApplication(PickleApiApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setAdditionalProfiles("isolated", "isolated-bootstrap");
            ConfigurableApplicationContext context = application.run(
                    "--spring.datasource.url=" + url,
                    "--spring.datasource.username=postgres",
                    "--spring.datasource.password=pickle-test",
                    "--PICKLE_DB_PASSWORD=pickle-test",
                    "--jobrunr.background-job-server.enabled=false",
                    "--jobrunr.dashboard.enabled=false",
                    "--pickle.jwt.secret=isolated-test-jwt-secret-0123456789abcdef",
                    "--pickle.credentials.encryption-key="
                            + "4IUuLkNP8ook5VuNU7By7GAq0J8rbFOcEfuExymtueM=",
                    "--pickle.terminal.enforce-single-instance=false",
                    "--pickle.isolated-bootstrap.enabled=true",
                    "--PICKLE_BOOTSTRAP_ADMIN_EMAIL=isolated-admin@example.test",
                    "--PICKLE_BOOTSTRAP_ADMIN_PASSWORD=S3cure-Isolated-Bootstrap-Pw!");
            assertThat(context.isActive()).isTrue();
            PickleApiApplication.closeAfterOneShot(context);
            assertThat(context.isActive()).isFalse();

            try (Connection connection = postgres.getPostgresDatabase().getConnection();
                    Statement statement = connection.createStatement()) {
                assertThat(singleLong(statement, "select count(*) from users where role='SYS_ADMIN'"
                        + " and status='ACTIVE' and email_verified_at is not null"
                        + " and email='isolated-admin@example.test' and position='STAFF'"
                        + " and department_other='플랫폼 운영'"))
                        .isEqualTo(1);
                assertThat(new BCryptPasswordEncoder(12).matches(
                        "S3cure-Isolated-Bootstrap-Pw!",
                        singleString(statement, "select password_hash from users"
                                + " where email='isolated-admin@example.test'")))
                        .isTrue();
                assertThat(singleLong(statement, "select count(*) from workspaces w"
                        + " join workspace_members m on m.workspace_id=w.id"
                        + " join users u on u.id=m.user_id where w.kind='PERSONAL'"
                        + " and m.role='OWNER' and u.email='isolated-admin@example.test'"))
                        .isEqualTo(1);
                assertThat(singleLong(statement, "select count(*) from llm_upstreams"))
                        .isEqualTo(3);
                assertThat(singleLong(statement, "select count(*) from nodes")
                        + singleLong(statement, "select count(*) from settings")
                        + singleLong(statement, "select count(*) from orgs")
                        + singleLong(statement, "select count(*) from audit_logs"))
                        .isZero();
            }
        }
    }

    private static long singleLong(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String singleString(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
