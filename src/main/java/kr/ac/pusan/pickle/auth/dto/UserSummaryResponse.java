package kr.ac.pusan.pickle.auth.dto;

import java.util.UUID;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRole;

/** Contract schema {@code UserSummary}. */
public record UserSummaryResponse(UUID id, String email, String name, UserRole role) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getPublicId(), user.getEmail(), user.getName(),
                user.getRole());
    }
}
