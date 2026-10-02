package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

/**
 * Contract schema {@code UpdateOrgRequestMailRequest} — whether an account is
 * one of the people an organisation mails about new requests.
 */
public record UpdateOrgRequestMailRequest(
        @Schema(description = "이 기관의 신청 접수 메일을 받을지. 신청을 승인할 수 있는 역할(ORG_ADMIN, ORG_MANAGER)만 켤 수 있다. "
                + "실제 수신자는 기관의 현재 수신 방식과 지정 명단을 따릅니다.")
        @NotNull Boolean enabled,
        @NotNull @Min(0) Long expectedRevision) {
}
