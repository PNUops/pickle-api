package kr.ac.pusan.pickle.auth.dto;

import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRole;

/** Contract schema {@code UserSummary}. */
public record UserSummaryResponse(Long id, String email, String name, UserRole role) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}
