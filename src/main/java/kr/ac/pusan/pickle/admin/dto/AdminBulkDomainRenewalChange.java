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
                description = "새 사용 기한이며 미래 시각으로 지정해요. 기한이 지나도 연장하지 않은 이름은 해제 대상이 되고, 해제할 때 레코드를 삭제해요.")
        @NotNull(message = "새 사용 기한을 지정해 주세요.")
        Instant renewDueAt,

        @Schema(description = "기한을 바꾼 이유이며 대상마다 감사 기록에 남겨요.")
        @Nullable String reason) {
}
