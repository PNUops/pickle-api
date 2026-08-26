package kr.ac.pusan.pickle.orgs;

/**
 * Canonical <b>derived org membership</b> SQL (operator decision,
 * 2026-07-13): user U belongs to org O iff U is an ACTIVE member of a workspace
 * that has &ge;1 {@code request} or non-DELETED VM with {@code org_id = O}, or U
 * administers O. A regular user carries no organisation of its own — the org is
 * a per-request/VM boundary, so membership is derived from the workspace's
 * resources, and has always been able to span several organisations because a
 * shared workspace can hold resources of more than one. Used by announcements,
 * the admin workspace picker, and the org-admin audit scope.
 *
 * <p>Since V90 the admin side spans several organisations too, so the fragments
 * take an {@link OrgScope} rather than one id.
 */
public final class OrgMembershipSql {

    /**
     * EXISTS fragment: the workspace identified by {@code workspaceIdExpr} has at
     * least one request or non-DELETED VM in the scope. Binds the scope's ids
     * <b>twice</b>, in order.
     */
    public static String workspaceLinkedToOrg(String workspaceIdExpr, OrgScope scope) {
        return "(exists (select 1 from requests lr where lr.workspace_id = " + workspaceIdExpr
                + " and " + scope.inList("lr.org_id") + ")"
                + " or exists (select 1 from vms lv where lv.workspace_id = " + workspaceIdExpr
                + " and " + scope.inList("lv.org_id") + " and lv.status <> 'DELETED'))";
    }

    /**
     * EXISTS fragment: the user identified by {@code userIdExpr} is a member
     * of at least one workspace linked to the scope (membership only — the caller
     * adds the user-ACTIVE condition where required). Binds the scope's ids
     * <b>twice</b>, in order.
     */
    public static String memberOfOrgLinkedWorkspace(String userIdExpr, OrgScope scope) {
        return "exists (select 1 from workspace_members lgm where lgm.user_id = " + userIdExpr
                + " and " + workspaceLinkedToOrg("lgm.workspace_id", scope) + ")";
    }

    private OrgMembershipSql() {
    }
}
