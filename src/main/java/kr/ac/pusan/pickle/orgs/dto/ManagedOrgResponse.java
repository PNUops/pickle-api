package kr.ac.pusan.pickle.orgs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;

/**
 * Contract schema {@code ManagedOrg} — one organisation an account holds a role
 * in, and the role it holds there. The role may be a read-only one, so this is
 * not only what the account administers.
 *
 * <p>Replaces the single {@code orgId} the account responses carried, which
 * could only name one organisation (V90).
 */
public record ManagedOrgResponse(
        @Schema(description = "기관 ID") UUID orgId,
        @Schema(description = "기관 이름") String orgName,
        @Schema(description = "이 기관에서의 역할") UserRole role) {
}
