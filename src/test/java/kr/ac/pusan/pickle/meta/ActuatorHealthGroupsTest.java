package kr.ac.pusan.pickle.meta;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Deployment probes stay independent from external dependency health. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, ActuatorHealthGroupsTest.ExternalDependencyConfig.class})
class ActuatorHealthGroupsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void externalDependencyFailureOnlyMakesAggregateHealthDown() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalDependencyConfig {

        @Bean
        HealthIndicator externalSmtpDnsHealthIndicator() {
            return () -> Health.down()
                    .withDetail("reason", "simulated external DNS failure")
                    .build();
        }
    }
}
