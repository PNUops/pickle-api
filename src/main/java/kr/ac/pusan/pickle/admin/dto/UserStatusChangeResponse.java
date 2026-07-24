package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code UserStatusChange} — one account status transition. */
public record UserStatusChangeResponse(
        UserStatus fromStatus,
        UserStatus toStatus,
        @Nullable Long actorId,
        @Nullable String actorEmail,
        @Nullable String reason,
        Instant changedAt) {
}
