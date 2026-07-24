package kr.ac.pusan.pickle.orgs;

/**
 * Canonical <b>derived org membership</b> SQL (operator decision,
 * 2026-07-13): user U belongs to org O iff U is an ACTIVE member of a group
 * that has ≥1 {@code vm_request} or non-DELETED VM with {@code org_id = O},
 * or U is an ORG_ADMIN with {@code users.org_id = O}. Regular users carry no
 * {@code users.org_id} (V11 {@code chk_users_org_role}) — the org is a
 * per-request/VM boundary, so membership is derived from the group's
 * resources. Used by announcements, the admin group picker, and the
 * org-admin audit scope.
 */
public final class OrgMembershipSql {

    /**
     * EXISTS fragment: the group identified by {@code groupIdExpr} has at
     * least one vm_request or non-DELETED VM in an org. Binds <b>two</b>
     * positional parameters (the org id, twice).
     */
    public static String groupLinkedToOrg(String groupIdExpr) {
        return "(exists (select 1 from vm_requests lr where lr.group_id = " + groupIdExpr
                + " and lr.org_id = ?)"
                + " or exists (select 1 from vms lv where lv.group_id = " + groupIdExpr
                + " and lv.org_id = ? and lv.status <> 'DELETED'))";
    }

    /**
     * EXISTS fragment: the user identified by {@code userIdExpr} is a member
     * of at least one group linked to an org (membership only — the caller
     * adds the user-ACTIVE condition where required). Binds <b>two</b>
     * positional parameters (the org id, twice).
     */
    public static String memberOfOrgLinkedGroup(String userIdExpr) {
        return "exists (select 1 from group_members lgm where lgm.user_id = " + userIdExpr
                + " and " + groupLinkedToOrg("lgm.group_id") + ")";
    }

    private OrgMembershipSql() {
    }
}
