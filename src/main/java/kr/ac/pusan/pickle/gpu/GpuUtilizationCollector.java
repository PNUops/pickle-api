package kr.ac.pusan.pickle.gpu;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Missing measurements return an empty list, never an invented zero. */
public interface GpuUtilizationCollector {
    record Observation(Instant sampledAt, double utilizationPercent, Integer memoryUsedMb,
            String source, int confidence, Map<String, Object> raw) {}
    List<Observation> collect(GpuAllocation allocation);
}
