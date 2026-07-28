package kr.ac.pusan.pickle.campusip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.campusip.CampusIpRequestStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract op {@code updateAdminCampusIpRequestStatus} body. Legal targets:
 * REQUESTED → APPROVED|REJECTED, APPROVED → GRANTED|REJECTED,
 * GRANTED → REVOKED. {@code grantedAddress} is required (IPv4) when the
 * target is GRANTED.
 */
public record UpdateCampusIpRequestStatusRequest(
        @NotNull
        @Schema(description = "전환할 상태")
        CampusIpRequestStatus status,
        @Nullable @Size(max = 45)
        @Schema(description = "할당된 교내 IP (GRANTED 전환 시 필수, IPv4)")
        String grantedAddress,
        @Nullable @Size(max = 1000)
        @Schema(description = "관리자 메모 (신청자 알림에 포함)")
        String adminNote) {
}
