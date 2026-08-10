package kr.ac.pusan.pickle.access;

/**
 * Who a grant is for (DB enum {@code access_grantee_type}): one named person,
 * or everyone in the resource's owning workspace.
 *
 * <p>A workspace-wide grant carries no workspace id — the workspace is always the one that
 * owns the resource, and storing it twice would only create a way for the two
 * to disagree.
 */
public enum AccessGranteeType {
    USER,
    WORKSPACE
}
