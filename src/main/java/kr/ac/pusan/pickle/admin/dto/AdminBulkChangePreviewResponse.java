package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Contract op {@code previewAdminBulkChange} response: one item per requested target, in request order. */
public record AdminBulkChangePreviewResponse(
        java.util.UUID previewId, java.time.Instant createdAt, java.time.Instant expiresAt,
        AdminBulkChangeTargetType targetType, @org.jspecify.annotations.Nullable AdminBulkChangeSpec change,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AdminBulkChangePreviewItem> items,
        @Schema(description = "이 미리보기로 이미 커밋된 본인 적용의 ID. 새 적용을 만들지 않고 기존 결과를 여는 데 씁니다.")
        @Nullable UUID consumedOperationId, @Nullable LlmAccountImpactProjection accountImpact) {
}
