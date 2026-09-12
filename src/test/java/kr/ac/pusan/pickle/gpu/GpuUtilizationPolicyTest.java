package kr.ac.pusan.pickle.gpu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kr.ac.pusan.pickle.gpu.GpuUtilizationPolicy.Assessment;
import kr.ac.pusan.pickle.gpu.GpuUtilizationPolicy.Sample;
import org.junit.jupiter.api.Test;

class GpuUtilizationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final Instant LONG_CONNECTION = NOW.minus(Duration.ofHours(48));

    @Test
    void missingMeasurementsAreNotAnIdleGpu() {
        Assessment result = assess(12, 5, List.of());

        assertThat(result.measurable()).isFalse();
        assertThat(result.lowUtilization()).isFalse();
        assertThat(result.coverage()).isZero();
        assertThat(result.averagePercent()).isNull();
        assertThat(result.latestSample()).isNull();
    }

    @Test
    void measuredZeroIsValidIdleEvidence() {
        Assessment result = assess(12, 5, regularSamples(12, 0));

        assertThat(result.measurable()).isTrue();
        assertThat(result.lowUtilization()).isTrue();
        assertThat(result.coverage()).isEqualTo(1);
        assertThat(result.averagePercent()).isZero();
    }

    @Test
    void aGpuWithoutAConnectionCannotBeReviewedFromOldMeasurements() {
        Assessment result = GpuUtilizationPolicy.evaluate(null, NOW, 12, 5,
                regularSamples(24, 0));

        assertThat(result.measurable()).isFalse();
        assertThat(result.lowUtilization()).isFalse();
        assertThat(result.averagePercent()).isNull();
    }

    @Test
    void theCurrentConnectionMustSpanTheWholeReviewWindow() {
        Instant attachedExactlyTwelveHoursAgo = NOW.minus(Duration.ofHours(12));
        List<Sample> samples = regularSamples(12, 0);

        Assessment mature = GpuUtilizationPolicy.evaluate(attachedExactlyTwelveHoursAgo,
                NOW, 12, 5, samples);
        Assessment young = GpuUtilizationPolicy.evaluate(attachedExactlyTwelveHoursAgo.plusSeconds(1),
                NOW, 12, 5, samples);

        assertThat(mature.measurable()).isTrue();
        assertThat(mature.lowUtilization()).isTrue();
        assertThat(young.measurable()).isFalse();
        assertThat(young.lowUtilization()).isFalse();
        assertThat(young.averagePercent()).isNull();
    }

    @Test
    void aPreviousConnectionCannotSupplyEvidenceForANewConnection() {
        List<Sample> oldAndNew = regularSamples(24, 0);
        Instant reattachedAt = NOW.minus(Duration.ofHours(1));

        Assessment result = GpuUtilizationPolicy.evaluate(reattachedAt, NOW, 12, 5, oldAndNew);

        assertThat(result.measurable()).isFalse();
        assertThat(result.lowUtilization()).isFalse();
        assertThat(result.averagePercent()).isNull();
    }

    @Test
    void exactlyEightyPercentCoverageIsEnoughButOneMissingIntervalIsNot() {
        List<Sample> full = regularSamples(10, 0);
        List<Sample> eightyPercent = new ArrayList<>(full.subList(0, 95));
        eightyPercent.add(full.getLast());
        List<Sample> oneIntervalShort = new ArrayList<>(eightyPercent);
        oneIntervalShort.removeFirst();

        Assessment accepted = assess(10, 5, eightyPercent);
        Assessment rejected = assess(10, 5, oneIntervalShort);

        assertThat(accepted.coverage()).isEqualTo(0.8);
        assertThat(accepted.measurable()).isTrue();
        assertThat(accepted.lowUtilization()).isTrue();
        assertThat(rejected.coverage()).isLessThan(0.8);
        assertThat(rejected.measurable()).isFalse();
        assertThat(rejected.lowUtilization()).isFalse();
        assertThat(rejected.averagePercent()).isNull();
    }

    @Test
    void theTwelveHourWindowNeedsOneHundredSixteenDistinctIntervals() {
        List<Sample> full = regularSamples(12, 0);
        List<Sample> samples = new ArrayList<>(full.subList(0, 114));
        samples.add(full.getLast());

        assertThat(assess(12, 5, samples).measurable()).isFalse();
        samples.add(full.get(114));
        assertThat(assess(12, 5, samples).measurable()).isTrue();
    }

    @Test
    void tenMinuteFreshnessBoundaryIsInclusive() {
        List<Sample> earlier = regularSamples(12, 0).stream()
                .filter(sample -> sample.sampledAt().isBefore(NOW.minus(Duration.ofMinutes(15))))
                .toList();
        List<Sample> freshEnough = new ArrayList<>(earlier);
        freshEnough.add(new Sample(NOW.minus(Duration.ofMinutes(10)), 0, 100));
        List<Sample> justStale = new ArrayList<>(earlier);
        justStale.add(new Sample(NOW.minus(Duration.ofMinutes(10)).minusSeconds(1), 0, 100));

        assertThat(assess(12, 5, freshEnough).measurable()).isTrue();
        Assessment stale = assess(12, 5, justStale);
        assertThat(stale.coverage()).isGreaterThan(0.8);
        assertThat(stale.measurable()).isFalse();
        assertThat(stale.lowUtilization()).isFalse();
        assertThat(stale.averagePercent()).isNull();
    }

    @Test
    void aSamplingBurstCannotTurnOneIntervalIntoACompleteWindow() {
        List<Sample> burst = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            burst.add(new Sample(NOW.minusSeconds(120).plusMillis(i), 0, 100));
        }

        Assessment result = assess(12, 5, burst);

        assertThat(result.coverage()).isLessThan(0.01);
        assertThat(result.measurable()).isFalse();
        assertThat(result.lowUtilization()).isFalse();
    }

    @Test
    void aBurstOfIdleSamplesCannotOutvoteHoursOfBusyIntervals() {
        List<Sample> samples = new ArrayList<>(regularSamples(12, 50));
        for (int i = 0; i < 1_000; i++) {
            samples.add(new Sample(NOW.minusSeconds(120).plusMillis(i), 0, 100));
        }

        Assessment result = assess(12, 40, samples);

        assertThat(result.coverage()).isEqualTo(1);
        assertThat(result.measurable()).isTrue();
        assertThat(result.averagePercent()).isGreaterThan(45);
        assertThat(result.lowUtilization()).isFalse();
    }

    @Test
    void theThresholdIsStrictlyBelowAndNotLessThanOrEqual() {
        List<Sample> samples = regularSamples(12, 5);

        assertThat(assess(12, 5, samples).lowUtilization()).isFalse();
        assertThat(assess(12, 5.01, samples).lowUtilization()).isTrue();
        assertThat(assess(12, 0, regularSamples(12, 0)).lowUtilization()).isFalse();
    }

    @Test
    void changingTheWindowUsesTheNewSettingAgainstTheSameEvidence() {
        List<Sample> samples = regularSamples(12, 0);

        Assessment twelveHours = assess(12, 5, samples);
        Assessment twentyFourHours = assess(24, 5, samples);
        Assessment sixHours = assess(6, 5, samples);

        assertThat(twelveHours.measurable()).isTrue();
        assertThat(twelveHours.lowUtilization()).isTrue();
        assertThat(twentyFourHours.coverage()).isEqualTo(0.5);
        assertThat(twentyFourHours.measurable()).isFalse();
        assertThat(twentyFourHours.lowUtilization()).isFalse();
        assertThat(sixHours.coverage()).isEqualTo(1);
        assertThat(sixHours.measurable()).isTrue();
        assertThat(sixHours.lowUtilization()).isTrue();
    }

    @Test
    void invalidOrFutureSamplesCannotFabricateCoverageOrFreshness() {
        List<Sample> samples = new ArrayList<>();
        for (Sample sample : regularSamples(12, 0)) {
            samples.add(new Sample(sample.sampledAt(), 0, 0));
            samples.add(new Sample(sample.sampledAt(), Double.NaN, 100));
            samples.add(new Sample(sample.sampledAt(), Double.POSITIVE_INFINITY, 100));
            samples.add(new Sample(sample.sampledAt(), -1, 100));
            samples.add(new Sample(sample.sampledAt(), 101, 100));
            samples.add(new Sample(sample.sampledAt().plus(Duration.ofHours(24)), 0, 100));
        }
        samples.add(new Sample(NOW.minus(Duration.ofHours(12)).minusSeconds(1), 0, 100));

        Assessment result = assess(12, 5, samples);

        assertThat(result.coverage()).isZero();
        assertThat(result.measurable()).isFalse();
        assertThat(result.lowUtilization()).isFalse();
        assertThat(result.averagePercent()).isNull();
        assertThat(result.latestSample()).isNull();
    }

    @Test
    void anInvalidRecentSampleDoesNotRefreshAStaleWindow() {
        List<Sample> samples = new ArrayList<>(regularSamples(12, 0).stream()
                .filter(sample -> sample.sampledAt().isBefore(NOW.minus(Duration.ofMinutes(10))))
                .toList());
        samples.add(new Sample(NOW, Double.NaN, 100));
        samples.add(new Sample(NOW, 0, 0));

        Assessment result = assess(12, 5, samples);

        assertThat(result.coverage()).isGreaterThan(0.8);
        assertThat(result.latestSample()).isBefore(NOW.minus(Duration.ofMinutes(10)));
        assertThat(result.measurable()).isFalse();
        assertThat(result.lowUtilization()).isFalse();
    }

    private static Assessment assess(int hours, double threshold, List<Sample> samples) {
        return GpuUtilizationPolicy.evaluate(LONG_CONNECTION, NOW, hours, threshold, samples);
    }

    private static List<Sample> regularSamples(int hours, double utilization) {
        List<Sample> samples = new ArrayList<>();
        Instant start = NOW.minus(Duration.ofHours(hours));
        for (Instant time = start.plusSeconds(120); time.isBefore(NOW); time = time.plusSeconds(300)) {
            samples.add(new Sample(time, utilization, 100));
        }
        return samples;
    }
}
