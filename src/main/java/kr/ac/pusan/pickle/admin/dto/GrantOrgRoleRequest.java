package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionPermissionInput;

/**
 * Explicit permission replacement for an institution membership.
 */
public record GrantOrgRoleRequest(
        @Schema(description = "명시적으로 부여할 기관 업무와 범위예요. 표시 역할은 이 값에서 계산해요.")
        @NotNull @Valid InstitutionPermissionInput permission,
        @NotNull @Min(0) Long expectedRevision) {
}
