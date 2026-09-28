package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Bulk change kind {@code LLM_KEY_STATUS}: suspend, resume or revoke every target. */
public record AdminBulkLlmKeyStatusChange(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "SUSPEND는 활성 키를 정지하고, RESUME은 정지된 키를 되살리며, REVOKE는 폐기합니다. "
                        + "이미 그 상태인 키는 UNCHANGED로 답합니다.")
        @NotNull(message = "상태 변경 종류를 지정해 주세요.")
        AdminBulkLlmKeyStatusAction action,

        @Schema(description = "정지 사유. SUSPEND에서는 필수이고 감사 기록에 남습니다.")
        @Size(max = 500, message = "정지 사유는 500자 이하여야 합니다.")
        @Nullable String reason) {
}
