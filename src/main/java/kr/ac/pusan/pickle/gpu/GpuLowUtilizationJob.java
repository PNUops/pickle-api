package kr.ac.pusan.pickle.gpu;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.stereotype.Component;

/** Reads current stored thresholds at evaluation time, without changing any lease. */
@Component
public class GpuLowUtilizationJob {
    private final GpuStore store;
    private final SettingsService settings;
    private final GpuReviewService reviews;
    private final GpuReclaimPolicy policy;
    private final GpuNotices notices;
    private final Clock clock;
    public GpuLowUtilizationJob(GpuStore store, SettingsService settings, GpuReviewService reviews,
            GpuReclaimPolicy policy, GpuNotices notices, Clock clock) {
        this.store = store; this.settings = settings; this.reviews = reviews; this.policy = policy; this.notices = notices; this.clock = clock;
    }
    @Recurring(id = "gpu-utilization-review", interval = "PT1H")
    @Job(name = "gpu-utilization-review", retries = 0)
    public void check() {
        for (GpuAllocation a : store.held()) {
            if (a.status() != GpuAllocationStatus.ALLOCATED) { continue; }
            try {
                if (a.connectionStatus() == GpuConnectionStatus.NONE && a.unattachedSince() != null) {
                    int hours = settings.requiredValid(SettingsService.GPU_UNATTACHED_REVIEW_HOURS).asInt();
                    if (!a.unattachedSince().plus(Duration.ofHours(hours)).isAfter(clock.instant())) {
                        reviews.open(a.id(), "UNATTACHED", Map.of("reviewHours", hours, "unattachedSince", a.unattachedSince().toString(), "evaluatedAt", clock.instant().toString()));
                    }
                } else if (a.connectionStatus() == GpuConnectionStatus.ATTACHED) {
                    int hours = settings.requiredValid(SettingsService.GPU_LOW_UTIL_WINDOW_HOURS).asInt();
                    double threshold = settings.requiredValid(SettingsService.GPU_LOW_UTIL_THRESHOLD_PERCENT).asDouble();
                    var samples = store.jdbc().query("select sampled_at, util_percent, confidence from gpu_utilization_samples where allocation_id = ? and sampled_at >= ?",
                            (rs, row) -> new GpuUtilizationPolicy.Sample(GpuStore.instant(rs, "sampled_at"), rs.getDouble("util_percent"), rs.getInt("confidence")),
                            a.id(), Timestamp.from(clock.instant().minus(Duration.ofHours(hours))));
                    var assessment = GpuUtilizationPolicy.evaluate(a.attachedAt(), clock.instant(), hours, threshold, samples);
                    if (policy.shouldReview(assessment)) {
                        reviews.open(a.id(), "LOW_UTILIZATION", Map.of("windowHours", hours, "thresholdPercent", threshold, "coverage", assessment.coverage(),
                                "averagePercent", assessment.averagePercent(), "latestSample", assessment.latestSample().toString(), "attachedAt", a.attachedAt().toString(), "evaluatedAt", clock.instant().toString()));
                    }
                }
            } catch (IllegalStateException e) { notices.administrators(e.getMessage(), "gpu.config:" + e.getMessage()); }
        }
    }
}
