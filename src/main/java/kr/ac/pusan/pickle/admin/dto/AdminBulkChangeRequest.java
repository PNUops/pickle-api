package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Contract ops {@code previewAdminBulkChange} and {@code applyAdminBulkChange}
 * take the same body; apply additionally needs every target's fingerprint from
 * the preview it is confirming.
 */
public record AdminBulkChangeRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "대상의 종류. 변경 종류와 맞아야 합니다.")
        @NotNull(message = "대상 종류를 지정해 주세요.")
        AdminBulkChangeTargetType targetType,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "대상의 공개 ID. 1개 이상 200개 이하, 중복 없이.")
        @NotNull(message = "대상을 지정해 주세요.")
        @Size(min = 1, max = 200, message = "대상은 1개 이상 200개 이하여야 합니다.")
        List<UUID> targetIds,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "변경 내용을 지정해 주세요.")
        @Valid AdminBulkChangeSpec change,

        @Schema(description = "적용에서만 씁니다. 미리보기가 준 대상별 fingerprint를 전부 담습니다. "
                + "서버는 적용 시점의 값을 다시 계산해 다른 대상은 STALE로 답하고 쓰지 않습니다.")
        @Nullable Map<UUID, String> fingerprints) {
}
