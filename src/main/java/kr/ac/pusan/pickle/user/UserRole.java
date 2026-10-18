package kr.ac.pusan.pickle.user;

/**
 * Account display and coarse controller admission. Institution display roles are derived
 * from complete FULL/SELECTED sources; this enum never supplies a work or workspace scope.
 * SYS operation allow-lists and personal OWNER/ACL standing remain independent.
 */
public enum UserRole {
    USER,
    ORG_VIEWER,
    ORG_MANAGER,
    ORG_ADMIN,
    SYS_VIEWER,
    SYS_MANAGER,
    SYS_ADMIN;

    /** An institution display tier, never proof of a particular work or target. */
    public boolean isOrgTier() {
        return this == ORG_ADMIN || this == ORG_MANAGER || this == ORG_VIEWER;
    }

    /**
     * Sys tier: not scoped to any organisation. Their {@code @PreAuthorize}
     * grants differ, and this is also the 2FA enforcement boundary — a system
     * viewer reads every organisation's data, personal data included, so it is
     * inside it.
     */
    public boolean isSysTier() {
        return this == SYS_ADMIN || this == SYS_MANAGER || this == SYS_VIEWER;
    }
}
