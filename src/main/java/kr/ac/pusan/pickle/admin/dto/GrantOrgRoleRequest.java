package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.user.UserRole;

/**
 * Contract schema {@code GrantOrgRole} — the role to give an account in one
 * organisation. A viewer role is how one organisation lets another's staff see
 * its resources without being able to change them.
 */
public record GrantOrgRoleRequest(
        @Schema(description = "이 기관에서 부여할 역할 (ORG_ADMIN, ORG_MANAGER, ORG_VIEWER)")
        @NotNull UserRole role) {
}
