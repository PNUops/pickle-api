package kr.ac.pusan.pickle.security;

import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;

/**
 * Authenticated principal placed in the SecurityContext by the JWT filter.
 *
 * <p>{@code id} is the internal key every query joins on; {@code publicId} is
 * the same account as the API names it, carried here so a response that has to
 * report who acted does not need a second read of the row the filter already
 * loaded.
 */
public record AuthenticatedUser(Long id, UUID publicId, String email, UserRole role, Long orgId) {
}
