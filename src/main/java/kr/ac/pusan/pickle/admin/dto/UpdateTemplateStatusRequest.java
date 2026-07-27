package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.inventory.TemplateStatus;

/** Contract op {@code updateAdminTemplate} body — the status toggle only. */
public record UpdateTemplateStatusRequest(
        @NotNull
        @Schema(description = "ACTIVE = 신청 위저드에 노출, DISABLED = 은퇴 (기존 VM 무영향)")
        TemplateStatus status) {
}
