package kr.ac.pusan.pickle.gpu;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One representative per five-minute bucket prevents burst sampling from skewing coverage. */
public final class GpuUtilizationPolicy {
    private GpuUtilizationPolicy() {}
    public record Sample(Instant sampledAt, double utilizationPercent, int confidence) {}
    public record Assessment(boolean measurable, boolean lowUtilization, double coverage,
            Double averagePercent, Instant latestSample) {}

    public static Assessment evaluate(Instant attachedAt, Instant now, int windowHours,
            double threshold, List<Sample> samples) {
        Instant start = now.minus(Duration.ofHours(windowHours));
        if (attachedAt == null || attachedAt.isAfter(start)) {
            return new Assessment(false, false, 0, null, null);
        }
        int buckets = Math.multiplyExact(windowHours, 12);
        Map<Long, Sample> representative = new HashMap<>();
        samples.stream().filter(s -> !s.sampledAt().isBefore(start) && !s.sampledAt().isAfter(now))
                .filter(s -> !s.sampledAt().isBefore(attachedAt) && s.confidence() > 0 && s.confidence() <= 100)
                .filter(s -> Double.isFinite(s.utilizationPercent()) && s.utilizationPercent() >= 0 && s.utilizationPercent() <= 100)
                .sorted(Comparator.comparing(Sample::sampledAt))
                .forEach(s -> {
                    long bucket = Math.min(buckets - 1L, Duration.between(start, s.sampledAt()).toSeconds() / 300);
                    representative.put(bucket, s);
                });
        double coverage = (double) representative.size() / buckets;
        Instant latest = representative.values().stream().map(Sample::sampledAt).max(Comparator.naturalOrder()).orElse(null);
        if (coverage < 0.8 || latest == null || latest.isBefore(now.minus(Duration.ofMinutes(10)))) {
            return new Assessment(false, false, coverage, null, latest);
        }
        double average = representative.values().stream().mapToDouble(Sample::utilizationPercent).average().orElseThrow();
        return new Assessment(true, average < threshold, coverage, average, latest);
    }
}
