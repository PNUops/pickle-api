package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A current observation, never an approval, reservation, or host-inventory response. */
public record AdminVmPlacementPreviewResponse(UUID orgId, Instant observedAt,
        @Schema(description = "검증한 입력 이미지 식별자. 호환 replica를 입력하면 아래 image.id의 canonical 식별자와 다를 수 있습니다.")
        UUID inputImageId,
        @Schema(description = "입력 이미지와 같은 논리 revision의 canonical 이미지 정보. 입력 식별자는 inputImageId로 확인합니다.")
        AdminApprovalImageReference image, List<AdminVmPlacementCandidate> candidates,
        @Schema(description = "현재 한 VM의 제안 사양이 맞는 노드가 있는지. N명 전체 용량 예약이나 승인 성공을 보장하지 않습니다.")
        boolean fitsAnyOneNow) {

    public record AdminVmPlacementCandidate(UUID nodeId, String nodeName, boolean fitsOneNow) { }
}
