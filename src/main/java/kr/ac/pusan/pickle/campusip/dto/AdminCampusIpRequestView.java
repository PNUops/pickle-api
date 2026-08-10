package kr.ac.pusan.pickle.campusip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.campusip.CampusIpRequestStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code AdminCampusIpRequestView} — 신청 + VM 컨텍스트. */
public record AdminCampusIpRequestView(
        UUID id,
        UUID vmId,
        @Nullable String vmName,
        @Nullable UUID orgId,
        @Schema(description = "신청 목적")
        String purpose,
        @Schema(description = "사용할 포트 번호 목록")
        List<Integer> ports,
        @Schema(description = "신청 상태 (REQUESTED = 신청, APPROVED = 관리자 승인, "
                + "GRANTED = 교내 IP 연결 완료, REJECTED = 반려, REVOKED = 회수)")
        CampusIpRequestStatus status,
        @Nullable
        @Schema(description = "연결된 교내 IP 주소 (10.0.0.0/8)")
        String grantedAddress,
        @Nullable String adminNote,
        UUID requestedBy,
        @Nullable
        @Schema(description = "신청자 이메일")
        String requesterEmail,
        @Nullable UUID processedBy,
        @Nullable Instant processedAt,
        Instant createdAt) {
}
