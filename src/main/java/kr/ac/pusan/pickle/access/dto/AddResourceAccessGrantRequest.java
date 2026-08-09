package kr.ac.pusan.pickle.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.access.AccessGranteeType;
import kr.ac.pusan.pickle.access.ResourceRole;
import org.jspecify.annotations.Nullable;

/** Adds one entry to a VM's access list. */
public record AddResourceAccessGrantRequest(
        @Schema(description = "USER는 지정된 사용자 한 명, WORKSPACE은 소유 워크스페이스 전체")
        @NotNull AccessGranteeType granteeType,
        @Schema(description = "대상 사용자 id. granteeType이 USER일 때만 보내며, 소유 워크스페이스의 구성원이어야 합니다.")
        @Nullable Long userId,
        @Schema(description = "부여할 등급. 워크스페이스 전체 항목에는 MEMBER 또는 VIEWER만 지정할 수 있습니다.")
        @NotNull ResourceRole role) {
}
