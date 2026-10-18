package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

/**
 * Contract schema {@code UpdateOrgRequestMailRequest} — whether an account is
 * one of the people an organisation mails about new requests.
 */
public record UpdateOrgRequestMailRequest(
        @Schema(description = "이 기관의 신청 접수 메일 수신 지정. 실제 신청 결정 업무가 있는 담당자만 켤 수 있습니다. "
                + "실제 수신자는 현재 수신 방식과 신청 종류, 실제 기관과 workspace 범위 및 계정 상태를 따릅니다.")
        @NotNull Boolean enabled,
        @NotNull @Min(0) Long expectedRevision) {
}
