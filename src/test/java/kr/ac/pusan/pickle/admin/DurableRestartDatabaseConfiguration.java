package kr.ac.pusan.pickle.admin;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Explicitly imported only by the two application lifetimes of the restart test. */
@TestConfiguration(proxyBeanMethods = false)
class DurableRestartDatabaseConfiguration {
    @Bean(destroyMethod = "")
    @Primary
    DataSource restartDataSource() {
        return java.util.Objects.requireNonNull(AdminBulkChangeDurabilityTest.restartDatabase,
                "test database must be bound before application startup");
    }
}
