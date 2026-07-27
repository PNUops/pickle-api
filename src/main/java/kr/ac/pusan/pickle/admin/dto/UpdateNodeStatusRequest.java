package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.inventory.NodeStatus;

/** Contract op {@code updateAdminNode} body — the status transition only. */
public record UpdateNodeStatusRequest(
        @NotNull
        @Schema(description = "ACTIVE만 신규 VM 배치 대상 — MAINTENANCE/OFFLINE은 배치 제외 (기존 게스트 무영향)")
        NodeStatus status) {
}
