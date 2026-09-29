package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Bulk change kind {@code DOMAIN_RENEWAL}: one new renewal deadline for every
 * target. The same two fields, and the same rules, as the single renewal
 * change.
 */
public record AdminBulkDomainRenewalChange(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "새 사용 기한. 미래 시각이어야 하며, 이 시각까지 연장하지 않으면 레코드가 내려가고 이름이 해제됩니다.")
        @NotNull(message = "새 사용 기한을 지정해 주세요.")
        Instant renewDueAt,

        @Schema(description = "기한을 바꾼 이유. 대상마다 감사 기록에 남습니다.")
        @Nullable String reason) {
}
