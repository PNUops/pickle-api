package kr.ac.pusan.pickle.user;

/**
 * Global user role (contract schema {@code UserRole}), in increasing order of
 * privilege — the order is load-bearing, because an account's effective role is
 * the highest one it holds across its organisations.
 *
 * <p>Each tier is three deep. An administrator does everything in its tier; an
 * operator does the daily work but none of the consequential mutations; a
 * viewer only reads. The viewer roles exist so that organisations can give each
 * other sight of their resources without giving each other the ability to touch
 * them: an organisation grants a viewer role <b>in itself</b> to somebody who
 * administers another.
 */
public enum UserRole {
    USER,
    ORG_VIEWER,
    ORG_MANAGER,
    ORG_ADMIN,
    SYS_VIEWER,
    SYS_MANAGER,
    SYS_ADMIN;

    /**
     * Org tier: scoped to the organisations the account holds a role in, which
     * since V90 can be several and can carry a different role in each. Kept out
     * of {@code @PreAuthorize} strings, which stay explicit allow-lists.
     *
     * <p>Careful: this says the account is <b>org-scoped</b>, not that it may
     * act. A guard asking what may be changed asks
     * {@code AuthenticatedUser.operates}, which excludes the viewer.
     */
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
