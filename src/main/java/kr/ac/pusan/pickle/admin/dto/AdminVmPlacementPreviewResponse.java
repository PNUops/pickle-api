package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.inventory.NodeStatus;
import org.jspecify.annotations.Nullable;

/** A current observation, never an approval, reservation, or host-inventory response. */
public record AdminVmPlacementPreviewResponse(UUID orgId, Instant observedAt,
        @Schema(description = "검증한 입력 이미지 ID예요. 호환 replica를 입력하면 image.id의 canonical ID와 다를 수 있어요.")
        UUID inputImageId,
        @Schema(description = "입력 이미지와 같은 논리 revision의 canonical 이미지 정보예요.")
        AdminApprovalImageReference image, List<AdminVmPlacementCandidate> candidates,
        @Schema(description = "현재 확인된 후보에 한 대 이상 배치할 수 있는지 나타내요.") boolean fitsAnyOneNow,
        int count, @Nullable UUID nodeId, int placeableCount, int shortfallCount, boolean fitsAllNow,
        PlacementTotals totals) {

    @Schema(description = "배치 예산의 자원 수치예요. null은 해당 축에 합계 배치 한도가 없거나 확인할 수 없다는 뜻이며 0과 달라요.")
    public record PlacementResources(@Nullable Long vcpu, @Nullable Long memoryMb, @Nullable Long diskGb) { }

    @Schema(description = "현재 확인된 유효 후보만 합산해요. 후보가 없으면 각 축은 0이에요. after는 배치 가능한 수량을 계획한 뒤의 잔여량이며 부족 대수는 shortfallCount로 구분해요.")
    public record PlacementTotals(PlacementResources budget, PlacementResources allocated,
            PlacementResources remaining, PlacementResources after) { }

    public enum PlacementLiveStatus { REACHABLE, UNKNOWN }
    public enum PlacementCapacityMode { PREPARED, LEGACY, INVALID }
    public enum PlacementResourceAxis { CPU, MEMORY, DISK }
    public enum PlacementExclusionReason {
        INACTIVE_NODE, IMAGE_UNAVAILABLE, NODE_NOT_SELECTED, LIVE_STATUS_UNKNOWN,
        INVALID_CAPACITY, DISK_BELOW_MINIMUM
    }

    public record AdminVmPlacementCandidate(UUID nodeId, String nodeName, boolean fitsOneNow,
            NodeStatus status,
            @Schema(description = "실제 상태 조회의 응답 여부예요. UNKNOWN은 꺼짐을 확정하지 않으며 확인된 가용량에서 제외해요.")
            PlacementLiveStatus liveStatus, Instant liveObservedAt, boolean eligible,
            List<PlacementExclusionReason> exclusionReasons, PlacementCapacityMode capacityMode,
            @Nullable PlacementResources budget, PlacementResources allocated,
            @Nullable PlacementResources remaining, @Nullable PlacementResources after,
            @Schema(description = "이 노드에 배치할 수 있는 수량이며 요청 count를 넘지 않아요.") int placeableCount,
            @Schema(description = "자동 배치 우선순위 또는 지정 노드로 계산한 이번 제안의 배치 대수예요.") int plannedCount,
            List<PlacementResourceAxis> limitingResources) { }
}
