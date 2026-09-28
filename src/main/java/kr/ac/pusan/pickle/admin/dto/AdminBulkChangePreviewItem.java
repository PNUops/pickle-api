package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One target as the preview judged it. */
public record AdminBulkChangePreviewItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID targetId,
        @Schema(description = "대상 이름. 범위 밖이면 없습니다.") @Nullable String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "true면 적용할 수 있습니다. fields가 비어 있으면 이미 그 값이라 바뀌지 않습니다.")
        boolean applicable,
        @Schema(description = "applicable이 false인 이유") @Nullable AdminBulkChangeReason reason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "바뀔 필드만")
        List<AdminBulkChangeFieldDiff> fields,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "이 대상의 현재 값을 요약한 값. 적용 요청에 그대로 돌려보냅니다.")
        String fingerprint) {
}
