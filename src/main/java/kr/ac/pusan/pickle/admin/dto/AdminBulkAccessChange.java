package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceRole;
import org.jspecify.annotations.Nullable;

/**
 * Bulk change kind {@code ACCESS}: one person's grant on every target.
 *
 * <p>Only a grant that names a person; workspace-wide grants are not part of
 * this change. The person must be active and a member of the workspace that
 * owns each target, as on the owner's own access list.
 */
public record AdminBulkAccessChange(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "접근 권한을 받거나 잃을 사용자")
        @NotNull(message = "대상 사용자를 지정해 주세요.")
        UUID userId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "GRANT는 없는 권한을 만들고, CHANGE는 있는 권한의 등급을 바꾸며, REVOKE는 권한을 지웁니다.")
        @NotNull(message = "접근 권한 작업 종류를 지정해 주세요.")
        AdminBulkAccessAction action,

        @Schema(description = "부여하거나 바꿀 등급. GRANT와 CHANGE에서 필수입니다.")
        @Nullable ResourceRole role) {
}
