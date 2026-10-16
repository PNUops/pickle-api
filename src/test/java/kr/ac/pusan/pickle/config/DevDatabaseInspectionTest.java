package kr.ac.pusan.pickle.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;

class DevDatabaseInspectionTest {

    @Test
    void refusesUnmarkedDataBeforeContextInitializationAndAcceptsOnlyEmptyOrMarkedDatabases()
            throws Exception {
        try (var postgres = EmbeddedPostgres.start()) {
            String url = "jdbc:postgresql://127.0.0.1:" + postgres.getPort() + "/postgres";
            assertThatCode(() -> DevEnvironmentGuard.inspectDatabase(url, "postgres", ""))
                    .doesNotThrowAnyException();
            try (var connection = postgres.getPostgresDatabase().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("create table retained_data (id integer)");
                statement.execute("insert into retained_data values (42)");
                assertThatThrownBy(() -> DevEnvironmentGuard.inspectDatabase(url, "postgres", ""))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("marker");
                AtomicBoolean initialized = new AtomicBoolean();
                SpringApplication application = new SpringApplication(EmptyConfiguration.class);
                application.addInitializers(context -> initialized.set(true));
                assertThatThrownBy(() -> application.run("--spring.profiles.active=dev",
                        "--spring.datasource.url=" + url, "--spring.datasource.username=postgres",
                        "--spring.datasource.password=", "--spring.main.web-application-type=none"))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("marker");
                assertThat(initialized).isFalse();
                try (var retained = statement.executeQuery("select id from retained_data")) {
                    assertThat(retained.next()).isTrue();
                    assertThat(retained.getInt(1)).isEqualTo(42);
                }
                statement.execute("create table settings (key text primary key, value jsonb)");
                statement.execute("insert into settings values ('dev_environment_marker', 'false')");
                assertThatThrownBy(() -> DevEnvironmentGuard.inspectDatabase(url, "postgres", ""))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("marker");
                statement.execute("update settings set value = 'true'");
                assertThatCode(() -> DevEnvironmentGuard.inspectDatabase(url, "postgres", ""))
                        .doesNotThrowAnyException();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
