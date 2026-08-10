package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code UserAdminView} — admin user-list item. */
public record UserAdminViewResponse(
        UUID id,
        String email,
        String name,
        UserRole role,
        @Nullable UUID orgId,
        UserStatus status,
        boolean mfaEnabled,
        Instant createdAt) {
}
