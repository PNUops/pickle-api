package kr.ac.pusan.pickle.gpu;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
public class GpuUtilizationCollectionJob {
    private final GpuStore store;
    private final GpuUtilizationCollector collector;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    public GpuUtilizationCollectionJob(GpuStore store, GpuUtilizationCollector collector, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.store = store; this.collector = collector; this.tx = tx; this.json = json; this.clock = clock;
    }
    @Recurring(id = "gpu-utilization-collection", interval = "PT5M")
    @Job(name = "gpu-utilization-collection", retries = 0)
    public void collect() {
        for (GpuAllocation observed : store.held()) {
            if (observed.connectionStatus() != GpuConnectionStatus.ATTACHED || observed.operationId() != null) { continue; }
            var samples = collector.collect(observed);
            if (samples.isEmpty()) { continue; }
            tx.executeWithoutResult(ignored -> {
                GpuAllocation a = store.lock(observed.id());
                if (a.connectionStatus() != GpuConnectionStatus.ATTACHED || !Objects.equals(a.attachedAt(), observed.attachedAt())) { return; }
                for (var sample : samples) {
                    if (sample.sampledAt() == null || sample.sampledAt().isBefore(a.attachedAt()) || sample.sampledAt().isAfter(clock.instant())) { continue; }
                    if (!Double.isFinite(sample.utilizationPercent()) || sample.utilizationPercent() < 0 || sample.utilizationPercent() > 100) { continue; }
                    store.jdbc().update("""
                            insert into gpu_utilization_samples(allocation_id,gpu_id,sampled_at,util_percent,mem_used_mb,source,confidence,raw)
                            values (?,?,?,?,?,?,?,?::jsonb) on conflict do nothing
                            """, a.id(), a.gpuId(), Timestamp.from(sample.sampledAt()), sample.utilizationPercent(), sample.memoryUsedMb(),
                            sample.source(), sample.confidence(), json.writeValueAsString(sample.raw()));
                }
            });
        }
    }
}
