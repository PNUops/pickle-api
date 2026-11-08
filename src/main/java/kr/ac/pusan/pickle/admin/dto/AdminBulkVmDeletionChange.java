package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Bulk change kind {@code VM_DELETION}: schedule or cancel deletion on every target. */
public record AdminBulkVmDeletionChange(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "SCHEDULE은 관리자 삭제를 예약하고 CANCEL은 접수된 삭제를 취소해요.")
        @NotNull(message = "삭제 작업 종류를 지정해 주세요.")
        AdminBulkVmDeletionAction action,

        @Schema(description = "삭제 예정 시각이며 SCHEDULE에서 필수로 미래 시각을 지정해요.")
        @Nullable Instant scheduledFor,

        @Schema(description = "삭제 사유이며 SCHEDULE에서 필수로 입력해요. VM과 워크스페이스의 활성 소유자에게 보내는 안내에 포함해요.")
        @Size(max = 2000, message = "삭제 사유는 2000자 이내로 입력해 주세요.")
        @Nullable String reason) {
}
