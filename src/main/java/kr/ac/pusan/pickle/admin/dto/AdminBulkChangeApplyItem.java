package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One target as the apply left it. */
public record AdminBulkChangeApplyItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID targetId,
        @Schema(description = "대상 이름. 범위 밖이면 없습니다.") @Nullable String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AdminBulkChangeResult result,
        @Schema(description = "SKIPPED인 이유") @Nullable AdminBulkChangeReason reason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "APPLIED면 바뀐 필드, 아니면 빈 배열")
        List<AdminBulkChangeFieldDiff> fields) {
}
