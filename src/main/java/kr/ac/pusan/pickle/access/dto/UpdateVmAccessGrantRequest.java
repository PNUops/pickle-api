package kr.ac.pusan.pickle.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.access.ResourceRole;

/** Changes the rung of one existing access-list entry. */
public record UpdateVmAccessGrantRequest(
        @Schema(description = "새 등급. 워크스페이스 전체 항목에는 MEMBER 또는 VIEWER만 지정할 수 있습니다.")
        @NotNull ResourceRole role) {
}
