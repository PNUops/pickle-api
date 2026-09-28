package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Bulk change kind {@code LLM_KEY_EXPIRY}: one new last day of use for every target. */
public record AdminBulkLlmKeyExpiryChange(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "새 종료일(KST, 이 날까지 포함). 키는 다음 날 0시에 만료됩니다. 오늘 이후여야 합니다.")
        @NotNull(message = "종료일을 입력해 주세요.")
        LocalDate endDate) {
}
