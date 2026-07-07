package kr.ac.pusan.pickle.security;

import kr.ac.pusan.pickle.user.UserRole;

/** Authenticated principal placed in the SecurityContext by the JWT filter. */
public record AuthenticatedUser(Long id, String email, UserRole role, Long orgId) {
}
