package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;

/** Contract schema {@code UserAdminView} — admin user-list item. */
public record UserAdminViewResponse(
        Long id,
        String email,
        String name,
        UserRole role,
        Long orgId,
        UserStatus status,
        boolean mfaEnabled,
        Instant createdAt) {
}
