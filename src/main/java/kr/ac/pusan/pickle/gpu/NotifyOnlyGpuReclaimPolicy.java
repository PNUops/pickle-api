package kr.ac.pusan.pickle.gpu;

import org.springframework.stereotype.Component;

@Component
public class NotifyOnlyGpuReclaimPolicy implements GpuReclaimPolicy {
    @Override public boolean shouldReview(GpuUtilizationPolicy.Assessment assessment) {
        return assessment.measurable() && assessment.lowUtilization();
    }
}
