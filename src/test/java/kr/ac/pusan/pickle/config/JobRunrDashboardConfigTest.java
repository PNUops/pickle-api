package kr.ac.pusan.pickle.config;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.jobrunr.dashboard.JobRunrDashboardWebServer;
import org.jobrunr.spring.autoconfigure.JobRunrProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Binding smoke test for the JobRunr dashboard settings: off by default (the
 * env-driven {@code PICKLE_JOBRUNR_DASH_ENABLED} toggle defaults to false and
 * the test profile pins it), port 8000, and basic-auth credentials mapped from
 * {@code PICKLE_JOBRUNR_DASH_USER}/{@code PICKLE_JOBRUNR_DASH_PASS} (property
 * keys verified against the jobrunr-spring-boot-4-starter 8.7.1 metadata).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class JobRunrDashboardConfigTest {

    @Autowired
    private JobRunrProperties jobRunrProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void dashboardStaysOffInTests() {
        JobRunrProperties.Dashboard dashboard = jobRunrProperties.getDashboard();
        assertThat(dashboard.isEnabled()).isFalse();
        assertThat(dashboard.getPort()).isEqualTo(8000);
        // No env override in tests → empty credentials bind (and with empty
        // credentials JobRunr would run the dashboard unauthenticated, hence
        // the enabled=false default everywhere).
        assertThat(dashboard.getUsername()).isNullOrEmpty();
        assertThat(dashboard.getPassword()).isNullOrEmpty();
        assertThat(applicationContext.getBeanNamesForType(JobRunrDashboardWebServer.class))
                .as("dashboard web server bean must not exist when disabled")
                .isEmpty();
    }
}
