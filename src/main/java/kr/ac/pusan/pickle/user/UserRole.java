package kr.ac.pusan.pickle.user;

/**
 * Global user role (single role per user, contract schema {@code UserRole}).
 * Ordered by increasing privilege. Two reduced-permission operator tiers sit
 * below the two admin roles: {@code ORG_MANAGER} sits below {@code ORG_ADMIN}
 * and {@code SYS_MANAGER} below {@code SYS_ADMIN}.
 */
public enum UserRole {
    USER,
    ORG_MANAGER,
    ORG_ADMIN,
    SYS_MANAGER,
    SYS_ADMIN;

    /**
     * Org tier: an org-scoped admin ({@code ORG_ADMIN}) or its operator
     * ({@code ORG_MANAGER}). Both are pinned to their managed org (derived
     * membership) in the service layer identically; only their {@code @PreAuthorize}
     * grants differ. Kept out of {@code @PreAuthorize} strings, which stay
     * explicit allow-lists.
     */
    public boolean isOrgTier() {
        return this == ORG_ADMIN || this == ORG_MANAGER;
    }

    /**
     * Sys tier: a system admin ({@code SYS_ADMIN}) or its operator
     * ({@code SYS_MANAGER}). Both see every org (no org scoping); their
     * {@code @PreAuthorize} grants differ.
     */
    public boolean isSysTier() {
        return this == SYS_ADMIN || this == SYS_MANAGER;
    }
}
