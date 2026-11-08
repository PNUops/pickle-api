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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "대상 종류이며 변경 종류와 맞아야 해요.")
        @NotNull(message = "대상 종류를 지정해 주세요.")
        AdminBulkChangeTargetType targetType,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "대상의 공개 ID. 1개 이상 200개 이하, 중복 없이.")
        @NotNull(message = "대상을 지정해 주세요.")
        @Size(min = 1, max = 200, message = "대상은 1~200개를 지정해 주세요.")
        List<UUID> targetIds,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "변경 내용을 지정해 주세요.")
        @Valid AdminBulkChangeSpec change,

        @Schema(description = "적용 요청에서만 보내요. 미리보기에서 받은 대상별 fingerprint를 모두 담아요. "
                + "서버는 적용 시점의 값을 다시 계산해 지문이 다른 대상은 STALE로 응답하고 변경하지 않아요.")
        @Nullable Map<UUID, String> fingerprints,
        @Valid @Nullable Map<UUID, kr.ac.pusan.pickle.access.dto.AccessConfirmation> accessConfirmations,
        @Schema(description = "적용에서 필수인 저장 미리보기 ID") @Nullable UUID previewId,
        @Schema(description = "적용할 때 필수이며 응답이 없어 다시 요청할 때도 유지하는 클라이언트 생성 ID예요. batchId와 같아요.")
        @Nullable UUID operationId) {
    public AdminBulkChangeRequest(AdminBulkChangeTargetType targetType, List<UUID> targetIds,
            AdminBulkChangeSpec change, @Nullable Map<UUID, String> fingerprints) {
        this(targetType, targetIds, change, fingerprints, null, null, null);
    }

    public AdminBulkChangeRequest(AdminBulkChangeTargetType targetType, List<UUID> targetIds,
            AdminBulkChangeSpec change, @Nullable Map<UUID, String> fingerprints,
            @Nullable Map<UUID, kr.ac.pusan.pickle.access.dto.AccessConfirmation> accessConfirmations) {
        this(targetType, targetIds, change, fingerprints, accessConfirmations, null, null);
    }

}
