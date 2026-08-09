package kr.ac.pusan.pickle.orgs;

/**
 * Canonical <b>derived org membership</b> SQL (operator decision,
 * 2026-07-13): user U belongs to org O iff U is an ACTIVE member of a workspace
 * that has ≥1 {@code vm_request} or non-DELETED VM with {@code org_id = O},
 * or U is an ORG_ADMIN with {@code users.org_id = O}. Regular users carry no
 * {@code users.org_id} (V11 {@code chk_users_org_role}) — the org is a
 * per-request/VM boundary, so membership is derived from the workspace's
 * resources. Used by announcements, the admin workspace picker, and the
 * org-admin audit scope.
 */
public final class OrgMembershipSql {

    /**
     * EXISTS fragment: the workspace identified by {@code workspaceIdExpr} has at
     * least one vm_request or non-DELETED VM in an org. Binds <b>two</b>
     * positional parameters (the org id, twice).
     */
    public static String workspaceLinkedToOrg(String workspaceIdExpr) {
        return "(exists (select 1 from vm_requests lr where lr.workspace_id = " + workspaceIdExpr
                + " and lr.org_id = ?)"
                + " or exists (select 1 from vms lv where lv.workspace_id = " + workspaceIdExpr
                + " and lv.org_id = ? and lv.status <> 'DELETED'))";
    }

    /**
     * EXISTS fragment: the user identified by {@code userIdExpr} is a member
     * of at least one workspace linked to an org (membership only — the caller
     * adds the user-ACTIVE condition where required). Binds <b>two</b>
     * positional parameters (the org id, twice).
     */
    public static String memberOfOrgLinkedWorkspace(String userIdExpr) {
        return "exists (select 1 from workspace_members lgm where lgm.user_id = " + userIdExpr
                + " and " + workspaceLinkedToOrg("lgm.workspace_id") + ")";
    }

    private OrgMembershipSql() {
    }
}
