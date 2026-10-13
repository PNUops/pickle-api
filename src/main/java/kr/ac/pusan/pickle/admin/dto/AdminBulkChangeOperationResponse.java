package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Committed mutation, callback receipt and current observation are different evidence. */
public record AdminBulkChangeOperationResponse(UUID operationId, UUID previewId, Instant committedAt,
        @Schema(description = "COMMITTED는 DB 변경과 결과의 커밋입니다. 큐 접수나 외부 완료를 뜻하지 않습니다.")
        String status, AdminBulkChangeTargetType targetType, @Nullable AdminBulkChangeSpec change,
        AdminBulkChangeApplyResponse result, List<AdminBulkChangeEffect> effects,
        List<AdminBulkChangeCurrentObservation> observations) {
    @Schema(name = "AdminBulkChangeEffect")
    public record AdminBulkChangeEffect(UUID id, @Nullable UUID targetId, String kind, String outcome,
            @Nullable String externalReference, Instant registeredAt, @Nullable Instant observedAt,
            @Schema(description = "현재 JobRunr 상태 관측. SUCCEEDED도 외부 자원의 물리적 완료를 뜻하지 않습니다.")
            @Nullable String queueState, @Nullable Instant queueObservedAt) { }
    @Schema(name = "AdminBulkChangeCurrentObservation")
    public record AdminBulkChangeCurrentObservation(UUID targetId, Instant observedAt,
            @Nullable AdminBulkChangeReason refusal,
            @Schema(description = "현재 DB가 알고 있는 대상 값입니다. 요청과 값이 같아도 이 적용의 외부 완료를 증명하지 않습니다.")
            Map<String, Object> values) { }
}
