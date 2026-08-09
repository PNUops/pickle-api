package kr.ac.pusan.pickle.workspace;

/**
 * Standing inside an ownership workspace (contract schema {@code WorkspaceMemberRole}).
 *
 * <p>Two rungs, because that is all the workspace axis decides. An owner runs the
 * workspace: edits it, manages its members, appoints other owners, and holds a
 * permanent read plus deletion and access-list management over everything the
 * workspace owns. A member belongs to it and may request resources — the approval
 * step, not a rung, is what holds that line.
 *
 * <p>Several owners per workspace are allowed and normal; the last one cannot be
 * demoted, removed, or withdraw. What a person may do to any one resource is
 * the other axis entirely ({@code ResourceRole}, the per-resource access list).
 *
 * <p>Declared strongest-first so {@link #atLeast} can compare by {@code ordinal()}.
 */
public enum WorkspaceMemberRole {
    OWNER,
    MEMBER;

    /** True when this rung is at least as privileged as {@code min}. */
    public boolean atLeast(WorkspaceMemberRole min) {
        return ordinal() <= min.ordinal();
    }
}
