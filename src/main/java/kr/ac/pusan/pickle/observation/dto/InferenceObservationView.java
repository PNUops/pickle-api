package kr.ac.pusan.pickle.observation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "고정된 보고 원천의 장비 전체 표본이에요. 기관과 워크스페이스, 원인, 공개 모델 수요 필터에 따른 개인별 장비 점유율은 제공하지 않아요.")
public record InferenceObservationView(
        UUID sourceId,
        UUID upstreamId,
        @Nullable String upstreamName,
        Instant from,
        Instant to,
        int bucketSeconds,
        int sampleIntervalSeconds,
        int retentionDays,
        InferenceObservationEvidence evidence,
        List<InferenceObservationPoint> series) {

    @Schema(name = "InferenceObservationFreshness")
    public enum Freshness { NOT_REPORTED, FRESH, STALE }
    @Schema(name = "InferenceObservationValueState")
    public enum ValueState { OBSERVED, UNKNOWN, NOT_REPORTED, UNSUPPORTED, SOURCE_FAILED }
    @Schema(name = "InferenceObservationIntervalState")
    public enum IntervalState { VALID, PARTIAL, BASELINE, UNAVAILABLE }
    @Schema(name = "InferenceObservationRetentionState")
    public enum RetentionState { WITHIN, PARTIAL, OUTSIDE }

    @Schema(name = "InferenceObservationEvidence", description = "최신 원천 보고 시각과 요청 기간의 표본 품질을 구분해요. OUTSIDE의 기간 수치는 null이며 PARTIAL은 보존된 부분만 세요. truncated이면 읽은 표본 수는 하한이에요. 설치 상태와 무손실 수집, 기간 전체의 수집 완료를 보장하지 않아요.")
    public record InferenceObservationEvidence(
            Instant retainedFrom,
            RetentionState retentionState,
            @Nullable Instant latestObservedAt,
            @Nullable Instant latestReceivedAt,
            Freshness freshness,
            @Nullable Long samplesInWindow,
            @Nullable Long expectedSamples,
            @Nullable Long failedSamples,
            @Nullable Long baselineIntervals,
            @Nullable Long resetIntervals,
            @Nullable Long gapIntervals,
            boolean truncated) {
    }

    @Schema(name = "InferenceObservationPoint", description = "요청한 시작 시각을 포함하고 종료 시각을 제외한 버킷이에요. samples는 원천 observedAt에 따른 snapshot 행 수이며, gauge의 명시 측정 시각이나 완전 구간의 종료 시각과 다를 수 있어요. samples가 0이어도 개별 metric의 실제 측정값은 유효할 수 있어요. 보존 범위 밖 수치와 계산할 수 없는 비율은 null이며, 장비 측정값 0으로 채우지 않아요.")
    public record InferenceObservationPoint(Instant from, Instant to, RetentionState retentionState,
            @Nullable Long samples, @Nullable Long failedSamples,
            Map<String, InferenceObservationMetric> host, List<InferenceObservationEngine> engines) {
    }

    @Schema(name = "InferenceObservationEngine", description = "원래 물리 모델과 엔진, 수집 epoch, 프로세스, 구성 identity를 유지해요. identity가 없거나 달라지거나 reset이나 gap이 있으면 연속 비율을 계산하지 않아요.")
    public record InferenceObservationEngine(String modelName, String engineId, UUID epoch,
            @Nullable String bootId, @Nullable String processId, @Nullable String configId,
            IntervalState intervalState,
            long validIntervals, long baselineIntervals, long resetIntervals, long gapIntervals,
            Map<String, InferenceObservationMetric> gauges, Map<String, InferenceObservationMetric> rates,
            Map<String, InferenceObservationMetric> histogramMeans) {
    }

    @Schema(name = "InferenceObservationMetric", description = "gauge mean은 실제 측정 시각의 표본 평균이에요. 명시 sampleAt이 요청 기간이나 보존 범위 밖이면 UNKNOWN/null이며 현재 시각으로 옮기지 않아요. rate mean은 누적 차분 합/실제 시간 합, histogram mean은 sum 차분 합/count 차분 합이에요. 양끝이 요청 기간과 보존 범위 안인 완전 구간만 종료 시각의 버킷에 귀속하며, 부분 구간을 나눠 추정하지 않아요. min/max는 gauge 표본값 또는 완전 구간 평균의 범위이며 분위수는 아니에요. intervalSeconds는 해당 버킷에서 끝나는 완전 관측 구간의 실제 초 합으로 버킷 길이를 넘을 수 있고, gauge는 0이에요. 버킷 안의 정확한 요청 시각 분포나 연속 센싱, 시간 점유율을 뜻하지 않아요. null과 실제 0은 달라요.")
    public record InferenceObservationMetric(ValueState state, @Nullable Double mean,
            @Nullable Double min, @Nullable Double max,
            long observedSamples, double intervalSeconds) {
    }
}
