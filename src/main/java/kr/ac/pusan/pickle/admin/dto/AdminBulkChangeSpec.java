package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/**
 * What a bulk change does: one {@code kind}, and exactly the member that kind
 * reads. Every other member must be absent, so a request cannot carry two
 * changes and have the server pick one.
 */
public record AdminBulkChangeSpec(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "변경 종류. 같은 이름의 멤버 하나만 채웁니다.")
        @NotNull(message = "변경 종류를 지정해 주세요.")
        AdminBulkChangeKind kind,

        @Schema(description = "kind가 LLM_KEY_LIMITS일 때") @Valid
        @Nullable AdminBulkLlmKeyLimitsChange llmKeyLimits,

        @Schema(description = "kind가 LLM_KEY_STATUS일 때") @Valid
        @Nullable AdminBulkLlmKeyStatusChange llmKeyStatus,

        @Schema(description = "kind가 LLM_KEY_EXPIRY일 때") @Valid
        @Nullable AdminBulkLlmKeyExpiryChange llmKeyExpiry,

        @Schema(description = "kind가 VM_PERIOD일 때") @Valid
        @Nullable AdminBulkVmPeriodChange vmPeriod,

        @Schema(description = "kind가 VM_POWER일 때") @Valid
        @Nullable AdminBulkVmPowerChange vmPower,

        @Schema(description = "kind가 VM_DELETION일 때") @Valid
        @Nullable AdminBulkVmDeletionChange vmDeletion,

        @Schema(description = "kind가 ACCESS일 때") @Valid
        @Nullable AdminBulkAccessChange access) {
}
