package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code UpdateVmFlavorRequest} (v0.23.0) — partial edit;
 * every field optional, at least one required (service-validated). Editing
 * values only moves future baselines: granted specs are denormalized onto
 * requests/reviews/VMs at decision time. {@code name} is immutable (audit and
 * seed references stay stable).
 */
public record UpdateVmFlavorRequest(
        @Size(max = 100, message = "표시명은 100자 이하여야 합니다.")
        @Nullable String displayName,

        @Min(value = 1, message = "vCPU는 1 이상이어야 합니다.")
        @Nullable Integer vcpu,

        @Min(value = 256, message = "메모리는 256MiB 이상이어야 합니다.")
        @Nullable Integer memoryMb,

        @Min(value = 1, message = "디스크는 1GiB 이상이어야 합니다.")
        @Nullable Integer diskGb,

        @Size(max = 2000, message = "비고는 2000자 이하여야 합니다.")
        @Nullable String notes,

        @Schema(description = "ACTIVE = 신청 위저드에 노출, DISABLED = 은퇴 (기존 신청·VM 무영향)")
        @Nullable CatalogStatus status) {
}
