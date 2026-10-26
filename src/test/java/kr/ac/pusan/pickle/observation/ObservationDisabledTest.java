package kr.ac.pusan.pickle.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Disabling the writer cannot turn the new bounded history into permanent storage. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ObservationDisabledTest {
    @Autowired MockMvc mvc;
    @Autowired ObservationProperties properties;
    @Autowired ObservationRetention retention;
    @Autowired JdbcTemplate jdbc;

    @Test
    void defaultConfigurationRejectsTheMachineEndpoint() throws Exception {
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.configured()).isFalse();
        mvc.perform(post("/internal/inference-observations")
                        .with(request -> { request.setRemoteAddr("192.0.2.61"); return request; })
                        .header("Authorization", "Bearer synthetic-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledIngressStillHasRegisteredAndEffectiveNewTableExpiry() {
        assertThat(jdbc.queryForList("select trim(id) from jobrunr_recurring_jobs", String.class))
                .contains("inference-observation-retention");
        Instant old = Instant.now().minusSeconds(100L * 86400);
        UUID source = UUID.randomUUID();
        jdbc.update("""
                insert into inference_observation_samples(source_id,producer_epoch,sample_sequence,observed_at,observed_minute,
                    reporter_received_at,received_at,upstream_id,upstream_name,content_sha256,host,inference)
                values (?,?,1,?,?,?,?,?,'Synthetic expired source',?,?::jsonb,?::jsonb)
                """, source, UUID.randomUUID(), Timestamp.from(old), Math.floorDiv(old.getEpochSecond(), 60),
                Timestamp.from(old), Timestamp.from(old), UUID.randomUUID(), "0".repeat(64), "{}", "{}");
        retention.sweep();
        assertThat(jdbc.queryForObject("select count(*) from inference_observation_samples where source_id=?", Long.class, source)).isZero();
    }
}
