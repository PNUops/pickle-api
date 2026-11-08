package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A current observation, never an approval, reservation, or host-inventory response. */
public record AdminVmPlacementPreviewResponse(UUID orgId, Instant observedAt,
        @Schema(description = "검증한 입력 이미지 ID예요. 호환 replica를 입력하면 image.id의 canonical ID와 다를 수 있어요.")
        UUID inputImageId,
        @Schema(description = "입력 이미지와 같은 논리 revision의 canonical 이미지 정보예요. 입력 ID는 inputImageId로 확인해요.")
        AdminApprovalImageReference image, List<AdminVmPlacementCandidate> candidates,
        @Schema(description = "현재 한 VM의 제안 사양에 맞는 노드가 있는지 나타내요. N명 전체의 용량 예약이나 승인 성공을 보장하지 않아요.")
        boolean fitsAnyOneNow) {

    public record AdminVmPlacementCandidate(UUID nodeId, String nodeName, boolean fitsOneNow) { }
}
