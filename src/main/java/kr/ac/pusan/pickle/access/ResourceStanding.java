package kr.ac.pusan.pickle.access;

/**
 * What one requester may do to one resource, with the resource itself left out.
 *
 * <p>This is the whole of the two-axis model in one place: the rung comes from
 * the resource's access list and from nowhere else, and the one thing workspace
 * standing still carries — an owner's permanent read, deletion and grant
 * management — is a flag rather than a rung, so that no check for something
 * inside the resource can be satisfied by it.
 *
 * <p>It names no resource type on purpose. Every type's answers are computed by
 * {@link ResourceAccessResolver} and read through the predicates here, so the
 * meaning of a rung cannot drift between one type and the next.
 *
 * @param grantedRole           the rung from the access list, or null with no grant
 * @param owningWorkspaceMember whether the requester belongs to the owning workspace
 * @param standingRights        whether they are an owner of that workspace, which
 *                              carries deletion and grant management on every
 *                              resource the workspace owns, and nothing inside them
 */
public record ResourceStanding(ResourceRole grantedRole, boolean owningWorkspaceMember,
        boolean standingRights) {

    /**
     * The rung this requester acts at, which comes from the access list and
     * nowhere else. Null when the list does not name them — including for an
     * owner of the workspace, whose standing rights are deliberately not a rung
     * (operator, 2026-08-09).
     */
    public ResourceRole role() {
        return grantedRole;
    }

    public boolean atLeast(ResourceRole min) {
        return grantedRole != null && grantedRole.atLeast(min);
    }

    /**
     * True when the resource may only be shown as name, state and who owns it. A
     * workspace owner with no grant lands here too, with the access list and
     * deletion still open to them via {@link #manages()}.
     */
    public boolean limited() {
        return grantedRole == null && owningWorkspaceMember;
    }

    /** May manage the access list and delete: a resource owner, or a workspace owner. */
    public boolean manages() {
        return standingRights || grantedRole == ResourceRole.OWNER;
    }

    /**
     * The masking split, in the type's own words: 404 for someone the
     * resource's existence is hidden from, and an honest 403 for a member of
     * the owning workspace who can already see it listed but holds no grant.
     */
    public void requireVisible(ResourceAccessMessages messages) {
        if (grantedRole != null) {
            return;
        }
        throw owningWorkspaceMember ? messages.noGrant() : messages.notFound();
    }
}
