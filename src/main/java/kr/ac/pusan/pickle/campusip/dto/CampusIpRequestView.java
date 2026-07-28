package kr.ac.pusan.pickle.campusip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.campusip.CampusIpRequestStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code CampusIpRequestView} — one 교내 IP 신청. */
public record CampusIpRequestView(
        Long id,
        Long vmId,
        @Schema(description = "신청 목적")
        String purpose,
        @Schema(description = "공개할 포트 번호 목록 (중복 제거·오름차순 정규화)")
        List<Integer> ports,
        CampusIpRequestStatus status,
        @Nullable
        @Schema(description = "할당된 교내 IP (GRANTED 이후)")
        String grantedAddress,
        @Nullable
        @Schema(description = "관리자 메모")
        String adminNote,
        Long requestedBy,
        @Nullable Instant processedAt,
        Instant createdAt) {
}
