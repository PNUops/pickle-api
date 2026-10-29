package kr.ac.pusan.pickle.observation;

import java.sql.Timestamp;
import java.time.Clock;
import kr.ac.pusan.pickle.common.time.DatabaseTimestamps;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Physical cleanup is separate from the immediate expiry enforced on reads. */
@Component
public class ObservationRetention {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public ObservationRetention(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    @Recurring(id = "inference-observation-retention", interval = "PT1H")
    @Job(name = "inference-observation-retention", retries = 0)
    public void sweep() {
        Timestamp cutoff = DatabaseTimestamps.ceilingMicros(clock.instant().minusSeconds(ObservationLimits.RETENTION_DAYS * 86400L));
        for (int i = 0; i < ObservationLimits.GC_BATCHES; i++) {
            int deleted = jdbc.update("""
                    delete from inference_observation_samples where id in
                    (select id from inference_observation_samples where observed_at < ? or received_at < ? order by id limit ?)
                    """, cutoff, cutoff, ObservationLimits.GC_BATCH);
            if (deleted < ObservationLimits.GC_BATCH) break;
        }
    }
}
