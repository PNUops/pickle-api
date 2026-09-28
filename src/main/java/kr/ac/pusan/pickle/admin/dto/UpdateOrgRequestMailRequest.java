package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Contract schema {@code UpdateOrgRequestMailRequest} — whether an account is
 * one of the people an organisation mails about new requests.
 */
public record UpdateOrgRequestMailRequest(
        @Schema(description = "이 기관의 신청 접수 메일을 받을지. 신청을 승인할 수 있는 역할(ORG_ADMIN, ORG_MANAGER)만 켤 수 있다. "
                + "기관에 켠 사람이 한 명도 없으면 기관 관리자 전원이 받는다.")
        @NotNull Boolean enabled) {
}
