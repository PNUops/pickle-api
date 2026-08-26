package kr.ac.pusan.pickle.security;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;

/**
 * Authenticated principal placed in the SecurityContext by the JWT filter.
 *
 * <p>{@code id} is the internal key every query joins on; {@code publicId} is
 * the same account as the API names it, carried here so a response that has to
 * report who acted does not need a second read of the row the filter already
 * loaded.
 *
 * <p>{@code role} is the <b>effective</b> role: the highest one the account
 * holds anywhere, which is what the {@code @PreAuthorize} gates ask about.
 * {@code orgRoles} is the per-organisation detail behind it (V90) and is empty
 * for everyone but the org tier — a sys-tier account is not scoped to any org,
 * and a regular user's organisation is derived from its workspaces rather than
 * held here. Ask {@link #administers} rather than {@code role} whenever the
 * question is what this account may do in one particular organisation.
 */
public record AuthenticatedUser(Long id, UUID publicId, String email, UserRole role,
        Map<Long, UserRole> orgRoles) {

    public AuthenticatedUser {
        orgRoles = orgRoles == null ? Map.of() : Map.copyOf(orgRoles);
    }

    /**
     * The organisations this account may <b>act</b> in: those where it holds
     * ORG_ADMIN or ORG_MANAGER. Every write guard asks this. It is written as a
     * filter rather than the key set because a role that may only read is
     * coming, and a guard that asks "is there a row" would admit it.
     */
    public Set<Long> operatedOrgIds() {
        return orgRoles.entrySet().stream()
                .filter(entry -> entry.getValue() == UserRole.ORG_ADMIN
                        || entry.getValue() == UserRole.ORG_MANAGER)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The subset it administers. Every operation ORG_MANAGER is denied has to
     * ask for this rather than {@link #operatedOrgIds()}: administering one
     * organisation raises the effective role, and the effective role is what
     * the {@code @PreAuthorize} gate sees, so a gate that admits only
     * ORG_ADMIN admits this account everywhere it holds any role at all.
     */
    public Set<Long> administeredOrgIds() {
        return orgRoles.entrySet().stream()
                .filter(entry -> entry.getValue() == UserRole.ORG_ADMIN)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Holds ORG_ADMIN or ORG_MANAGER in {@code orgId}, so it may act there. */
    public boolean operates(Long orgId) {
        UserRole held = orgId == null ? null : orgRoles.get(orgId);
        return held == UserRole.ORG_ADMIN || held == UserRole.ORG_MANAGER;
    }

    /**
     * Holds {@code ORG_ADMIN} in {@code orgId} specifically. The operations
     * where ORG_ADMIN and ORG_MANAGER differ ask this, not {@link #role}:
     * administering one organisation must not confer an admin's rights in
     * another the account merely operates.
     */
    public boolean administers(Long orgId) {
        return orgId != null && orgRoles.get(orgId) == UserRole.ORG_ADMIN;
    }
}
