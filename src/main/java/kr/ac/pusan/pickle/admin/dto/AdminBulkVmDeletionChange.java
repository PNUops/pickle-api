package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Bulk change kind {@code VM_DELETION}: schedule or cancel deletion on every target. */
public record AdminBulkVmDeletionChange(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "SCHEDULE은 관리자 삭제를 예약하고, CANCEL은 접수된 삭제를 취소합니다.")
        @NotNull(message = "삭제 작업 종류를 지정해 주세요.")
        AdminBulkVmDeletionAction action,

        @Schema(description = "삭제 예정 시각. SCHEDULE에서는 필수이고 미래여야 합니다.")
        @Nullable Instant scheduledFor,

        @Schema(description = "삭제 사유. SCHEDULE에서는 필수이고 워크스페이스에 안내됩니다.")
        @Size(max = 2000, message = "삭제 사유는 2000자 이하여야 합니다.")
        @Nullable String reason) {
}
