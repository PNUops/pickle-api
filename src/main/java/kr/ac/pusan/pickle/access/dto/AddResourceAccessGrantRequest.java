package kr.ac.pusan.pickle.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import kr.ac.pusan.pickle.access.AccessGranteeType;
import kr.ac.pusan.pickle.access.ResourceRole;
import org.jspecify.annotations.Nullable;

/** Adds one entry to a VM's access list. */
public record AddResourceAccessGrantRequest(
        @Schema(description = "USER는 지정한 사용자 한 명, WORKSPACE는 소유 워크스페이스 전체예요.")
        @NotNull AccessGranteeType granteeType,
        @Schema(description = "대상 사용자 ID예요. granteeType이 USER일 때만 보내며, 소유 워크스페이스의 활성 구성원이어야 해요.")
        @Nullable UUID userId,
        @Schema(description = "부여할 등급이에요. 워크스페이스 전체 항목에는 MEMBER 또는 VIEWER만 지정할 수 있어요.")
        @NotNull ResourceRole role,
        @jakarta.validation.Valid @Nullable AccessConfirmation confirmation) {
    public AddResourceAccessGrantRequest(AccessGranteeType granteeType, @Nullable UUID userId, ResourceRole role) {
        this(granteeType, userId, role, null);
    }
}
