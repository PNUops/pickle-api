package kr.ac.pusan.pickle.campusip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.campusip.CampusIpRequestStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code CampusIpRequestView} — one 교내 IP 신청. */
public record CampusIpRequestView(
        UUID id,
        UUID vmId,
        @Schema(description = "신청 목적")
        String purpose,
        @Schema(description = "사용할 포트 번호 목록 (중복 제거·오름차순 정규화)")
        List<Integer> ports,
        @Schema(description = "신청 상태 (REQUESTED = 신청, APPROVED = 관리자 승인, "
                + "GRANTED = 교내 IP 연결 완료, REJECTED = 반려, REVOKED = 회수)")
        CampusIpRequestStatus status,
        @Nullable
        @Schema(description = "연결된 교내 IP 주소 (GRANTED 이후)")
        String grantedAddress,
        @Nullable
        @Schema(description = "관리자 메모")
        String adminNote,
        UUID requestedBy,
        @Nullable Instant processedAt,
        Instant createdAt) {
}
