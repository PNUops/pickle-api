package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView;

/** Scoped raw-event demand and independently labelled current configuration. */
@Schema(description = "요청 시각을 기준으로 관측한 수요예요. 최종 upstream 소비는 중간 재시도 부하나 개인별 GPU 점유율을 뜻하지 않아요.")
public record AdminLlmCapacityResponse(
        Instant generatedAt,
        String timezone,
        Instant from,
        Instant to,
        int bucketSeconds,
        CapacityCounts summary,
        List<CapacitySeriesPoint> series,
        List<CapacityReason> reasons,
        CapacityConsumers consumers,
        CapacitySelectors selectors,
        CapacityQuality quality,
        boolean telemetryVisible,
        CapacityTelemetry telemetry, @Nullable UUID openrouterAccountId) {

    public enum CapacityGroup { ORG, WORKSPACE, KEY }
    public enum CapacityReasonCategory {
        USER_QUOTA, ENDPOINT_POLICY, MODEL_POLICY, LARGE_REQUEST, GLOBAL_ADMISSION,
        PROVIDER, INPUT, AUTHENTICATION, SERVICE, CANCELED, UNCLASSIFIED
    }
    public enum CapacityRawHistoryState { NOT_SWEPT, WITHIN_RETAINED_RANGE, PARTIALLY_SWEPT, FULLY_SWEPT }
    public enum CapacityTelemetryStatus { NOT_CONFIGURED, NOT_PROVIDED, NOT_REPORTED, REPORTED }

    @Schema(description = "보존된 raw event에서 관측한 수치예요. 요청 기간 전체가 삭제 경계 밖이면 null이에요. 일부 기간이 삭제됐다면 quality를 함께 확인해요.")
    public record CapacityCounts(
            @Nullable Long requests,
            @Nullable Long succeeded,
            @Nullable Long rateLimited,
            @Nullable Long failed,
            @Nullable Long canceled,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long estimatedRequests,
            @Nullable Long tokenAxisRequests,
            @Nullable Long creditAxisRequests,
            @Nullable Long unknownAxisRequests,
            @Nullable Long unknownKeyRequests,
            @Nullable Long unknownModelRequests,
            @Schema(description = "최종 upstream이 기록되지 않은 요청 수예요. 모델 판정 전 거절과 과거 미귀속도 포함하며 특정 장비 수요로 보지 않아요.")
            @Nullable Long unknownFinalUpstreamRequests,
            @Schema(description = "공급자가 보고한 요청 금액의 합이에요. TOKEN/CREDIT 축과 독립된 값이며 사용자나 플랫폼의 부담액, 원가를 확정하지 않아요. 권한이나 가격 표본이 없으면 null이에요.")
            @Nullable BigDecimal costUsd,
            @Nullable Long pricedRequests,
            boolean financeVisible) {
    }

    public record CapacitySeriesPoint(Instant from, Instant to, CapacityRawHistoryState rawHistoryState, CapacityCounts counts) {
    }

    @Schema(description = "정상 응답을 제외한 원문 오류 유형과 분류의 조합이에요. GLOBAL_ADMISSION은 실제 attempts=0 근거가 있을 때만 분류하며 과거 시도 횟수가 미기록이면 미분류로 유지해요. PROVIDER는 공급 경로의 결과이며 공급자 과실이나 중간 처리 경로를 확정하지 않아요.")
    public record CapacityReason(@Nullable String errorType, CapacityReasonCategory category, long requests, long knownKeys) {
    }

    public record CapacityConsumers(CapacityGroup group, List<CapacityConsumer> items,
            int page, int size, long totalElements, int totalPages, LlmConsumerSort sort) {
    }

    public record CapacityConsumer(@Nullable UUID orgId, @Nullable String orgName,
            @Nullable UUID workspaceId, @Nullable String workspaceName,
            @Nullable UUID keyId, @Nullable String keyName,
            CapacityCounts counts, @Nullable CapacityCurrentLimits currentLimits) {
    }

    @Schema(description = "조회 시점에 저장된 키별 override예요. 과거 요청 당시 적용값이나 Gateway 반영 완료를 뜻하지 않아요. null RPM/TPM/동시성에 적용되는 실제 서비스 기본값은 보고되지 않았어요.")
    public record CapacityCurrentLimits(Instant configurationObservedAt,
            @Nullable Integer rpm, @Nullable Integer tpm, @Nullable Integer concurrency,
            @Schema(description = "저장된 일일 TOKEN 한도예요. null은 무제한이며 0은 TOKEN 축 사용 차단을 뜻해요.")
            @Nullable Long dailyTokens, boolean quotaExhausted,
            boolean serviceDefaultsReported) {
    }

    public record CapacityReference(UUID id, String name) {
    }

    public record CapacityReferenceCollection(List<CapacityReference> items, long totalItems, boolean truncated) {
    }

    @Schema(description = "현재 관측 업무가 허용한 실제 키 관계와 조회 가능한 등록 공급원의 최소 참조예요. 워크스페이스 관리나 전역 장비 권한을 뜻하지 않아요.")
    public record CapacitySelectors(CapacityReferenceCollection institutions,
            CapacityReferenceCollection workspaces, CapacityReferenceCollection upstreams) {
    }

    @Schema(description = "보존과 전달 품질이에요. latestUsageReceivedAt은 API 수신 시각이며 해당 시각까지의 수집 완료를 보장하지 않아요. 빈 집계만으로 미수집이나 전송 손실이 없다고 판단할 수 없어요.")
    public record CapacityQuality(CapacityRawHistoryState rawHistoryState,
            @Nullable Instant rawRetainedFrom, int configuredRetentionDays,
            @Nullable Instant latestUsageReceivedAt, @Nullable Instant rollupLastSuccessAt,
            String completeness, LlmGatewayStatusResponse gateway) {
    }

    @Schema(description = "SYS 전역 장비 관측과 기관 수요는 서로 다른 모집단이에요. 기관에는 상태 외의 전역 source와 장비, 엔진 값을 제공하지 않아요.")
    public record CapacityTelemetry(CapacityTelemetryStatus status, @Nullable InferenceObservationView device) {
    }
}
