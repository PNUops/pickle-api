package kr.ac.pusan.pickle.gpu;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pickle.gpu.collector", havingValue = "none", matchIfMissing = true)
public class NoGpuUtilizationCollector implements GpuUtilizationCollector {
    @Override public List<Observation> collect(GpuAllocation allocation) { return List.of(); }
}
