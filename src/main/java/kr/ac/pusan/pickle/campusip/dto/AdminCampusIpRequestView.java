package kr.ac.pusan.pickle.campusip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.campusip.CampusIpRequestStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code AdminCampusIpRequestView} — 신청 + VM 컨텍스트. */
public record AdminCampusIpRequestView(
        Long id,
        Long vmId,
        @Nullable String vmName,
        @Nullable Long orgId,
        @Schema(description = "신청 목적")
        String purpose,
        List<Integer> ports,
        CampusIpRequestStatus status,
        @Nullable String grantedAddress,
        @Nullable String adminNote,
        Long requestedBy,
        @Nullable
        @Schema(description = "신청자 이메일")
        String requesterEmail,
        @Nullable Long processedBy,
        @Nullable Instant processedAt,
        Instant createdAt) {
}
