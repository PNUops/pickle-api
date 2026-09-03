package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code UpdateRequestPeriodRequest}: partial edit. Every field
 * is optional and at least one is required (service-validated). {@code name} is
 * immutable so audit references stay stable.
 *
 * <p>Editing a date moves only future requests: a request copies the end date
 * at submission, so correcting next term's date never reaches one already
 * filed.</p>
 */
public record UpdateRequestPeriodRequest(
        @Size(max = 100, message = "표시명은 100자 이하여야 합니다.")
        @Nullable String displayName,

        @Schema(description = "새 종료일")
        @Nullable LocalDate endDate,

        @Nullable CatalogStatus status,

        @Nullable Integer displayOrder) {
}
