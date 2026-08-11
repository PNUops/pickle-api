package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code UserStatusChange} — one account status transition. */
public record UserStatusChangeResponse(
        UserStatus fromStatus,
        UserStatus toStatus,
        @Nullable UUID actorId,
        @Nullable String actorEmail,
        @Nullable String actorName,
        @Nullable String reason,
        Instant changedAt) {
}
