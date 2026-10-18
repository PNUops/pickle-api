package kr.ac.pusan.pickle.security;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.ac.pusan.pickle.security.institution.InstitutionActorContext;
import kr.ac.pusan.pickle.user.UserRole;
import org.jspecify.annotations.Nullable;

/** Current database identity, original bearer version, and a separately validated institution context. */
public record AuthenticatedUser(Long id, UUID publicId, String email, UserRole role,
        Map<Long, UserRole> orgRoles, @Nullable Integer authenticatedTokenVersion,
        InstitutionActorContext institutionContext) {
    public AuthenticatedUser {
        orgRoles = orgRoles == null ? Map.of() : Map.copyOf(orgRoles);
        Objects.requireNonNull(institutionContext);
        institutionContext.authority().ifPresent(authority -> {
            if (!authority.accountId().equals(publicId)) { throw new IllegalArgumentException("Institution authority must belong to this identity"); }
        });
    }

    /** Direct legacy construction supplies no verified institution context or bearer version. */
    public AuthenticatedUser(Long id, UUID publicId, String email, UserRole role, Map<Long, UserRole> orgRoles) {
        this(id, publicId, email, role, orgRoles, null,
                InstitutionActorContext.invalid(InstitutionActorContext.Failure.NOT_LOADED));
    }

}
