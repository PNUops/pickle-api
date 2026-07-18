package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.user.UserStatus;

/** Contract schema {@code UserStatusChange} — one account status transition. */
public record UserStatusChangeResponse(
        UserStatus fromStatus,
        UserStatus toStatus,
        Long actorId,
        String actorEmail,
        String reason,
        Instant changedAt) {
}
