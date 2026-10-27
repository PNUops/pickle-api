package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationView;

/** Scoped raw-event demand and independently labelled current configuration. */
@Schema(description = "요청 시각 기준의 관측된 수요. 최종 upstream 소비는 중간 재시도 부하나 개인별 GPU 점유율을 뜻하지 않습니다.")
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

    @Schema(description = "보존된 raw event의 관측 수. 요청 기간 전체가 삭제 경계 밖이면 수치는 null입니다. 부분 삭제 기간은 quality를 함께 확인해야 합니다.")
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
            @Schema(description = "최종 upstream 미기록 요청 수. 모델 판정 전 거절과 과거 미귀속도 포함하며 특정 장비 수요로 귀속하지 않습니다.")
            @Nullable Long unknownFinalUpstreamRequests,
            @Schema(description = "공급자가 보고한 요청 금액의 합. TOKEN/CREDIT 축과 독립이며 사용자·플랫폼 부담이나 원가를 확정하지 않습니다. 권한 없음 또는 가격 표본 없음은 null입니다.")
            @Nullable BigDecimal costUsd,
            @Nullable Long pricedRequests,
            boolean financeVisible) {
    }

    public record CapacitySeriesPoint(Instant from, Instant to, CapacityRawHistoryState rawHistoryState, CapacityCounts counts) {
    }

    @Schema(description = "정상 응답을 제외한 원문 오류 유형과 분류의 조합. GLOBAL_ADMISSION은 실제 attempts=0 근거가 있을 때만 분류하며 과거 시도 횟수 미기록은 미분류로 보존합니다. PROVIDER는 공급 경로 결과이며 공급자 과실이나 중간 처리 경로를 확정하지 않습니다.")
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

    @Schema(description = "조회 시점에 저장된 키별 override. 과거 요청 당시 적용값이나 gateway 반영 완료가 아닙니다. null RPM/TPM/동시성의 실제 서비스 기본값은 보고되지 않았습니다.")
    public record CapacityCurrentLimits(Instant configurationObservedAt,
            @Nullable Integer rpm, @Nullable Integer tpm, @Nullable Integer concurrency,
            @Schema(description = "저장된 일일 TOKEN 한도. null은 무제한, 0은 TOKEN 축 사용 불가입니다.")
            @Nullable Long dailyTokens, boolean quotaExhausted,
            boolean serviceDefaultsReported) {
    }

    public record CapacityReference(UUID id, String name) {
    }

    public record CapacityReferenceCollection(List<CapacityReference> items, long totalItems, boolean truncated) {
    }

    @Schema(description = "현재 관측 업무가 허용한 실제 키 관계와 보이는 등록 공급원의 최소 참조. 워크스페이스 관리나 전역 장비 권한이 아닙니다.")
    public record CapacitySelectors(CapacityReferenceCollection institutions,
            CapacityReferenceCollection workspaces, CapacityReferenceCollection upstreams) {
    }

    @Schema(description = "보존과 전달 품질. latestUsageReceivedAt은 API 수신 시각이며 completeness watermark가 아닙니다. 집계가 비어도 미수집·전송 손실이 없다는 뜻은 아닙니다.")
    public record CapacityQuality(CapacityRawHistoryState rawHistoryState,
            @Nullable Instant rawRetainedFrom, int configuredRetentionDays,
            @Nullable Instant latestUsageReceivedAt, @Nullable Instant rollupLastSuccessAt,
            String completeness, LlmGatewayStatusResponse gateway) {
    }

    @Schema(description = "SYS 전역 장비 관측과 기관 수요는 서로 다른 모집단입니다. 기관에는 상태 외 전역 source·장비·엔진 값을 제공하지 않습니다.")
    public record CapacityTelemetry(CapacityTelemetryStatus status, @Nullable InferenceObservationView device) {
    }
}
