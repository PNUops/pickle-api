package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.inventory.NodeStatus;

/** Contract op {@code updateAdminNode} body — the status transition only. */
public record UpdateNodeStatusRequest(
        @NotNull
        @Schema(description = "신규 VM은 ACTIVE 노드에만 배치해요. MAINTENANCE/OFFLINE은 배치에서 제외하며 기존 게스트에는 영향을 주지 않아요.")
        NodeStatus status) {
}
