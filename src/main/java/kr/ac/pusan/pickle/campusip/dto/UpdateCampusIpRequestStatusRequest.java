package kr.ac.pusan.pickle.campusip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.campusip.CampusIpRequestStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract op {@code updateAdminCampusIpRequestStatus} body. Legal targets:
 * REQUESTED → APPROVED|REJECTED, APPROVED → GRANTED|REJECTED,
 * GRANTED → REVOKED. {@code grantedAddress} is required when the target is
 * GRANTED and must be a campus address (10.0.0.0/8).
 */
public record UpdateCampusIpRequestStatusRequest(
        @NotNull
        @Schema(description = "전환할 상태 (APPROVED = 승인, GRANTED = 교내 IP 연결 완료, "
                + "REJECTED = 반려, REVOKED = 회수)")
        CampusIpRequestStatus status,
        @Nullable @Size(max = 45)
        @Schema(description = "연결한 교내 IP 주소 (GRANTED 전환 시 필수, 10.0.0.0/8 대역)")
        String grantedAddress,
        @Nullable @Size(max = 1000)
        @Schema(description = "관리자 메모 (신청자 알림에 포함)")
        String adminNote) {
}
