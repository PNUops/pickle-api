package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

/**
 * Contract schema {@code UpdateOrgRequestMailRequest} — whether an account is
 * one of the people an organisation mails about new requests.
 */
public record UpdateOrgRequestMailRequest(
        @Schema(description = "이 기관의 신청 접수 메일 수신 여부예요. 실제 신청 결정 업무가 있는 담당자만 켤 수 있어요. "
                + "실제 수신자는 현재 수신 방식과 신청 종류, 실제 기관과 워크스페이스 범위, 계정 상태를 기준으로 정해요.")
        @NotNull Boolean enabled,
        @NotNull @Min(0) Long expectedRevision) {
}
