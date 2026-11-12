package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** Contract op {@code applyAdminBulkChange} response. */
public record AdminBulkChangeApplyResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "이 적용의 ID이며 대상별 감사 기록과 요약 기록에 같은 값을 담아요.")
        UUID batchId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AdminBulkChangeApplyItem> items) {
}
