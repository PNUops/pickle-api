package kr.ac.pusan.pickle.group;

/**
 * Standing inside an ownership group (contract schema {@code GroupMemberRole}).
 *
 * <p>Two rungs, because that is all the group axis decides. An owner runs the
 * group: edits it, manages its members, appoints other owners, and holds a
 * permanent read plus deletion and access-list management over everything the
 * group owns. A member belongs to it and may request resources — the approval
 * step, not a rung, is what holds that line.
 *
 * <p>Several owners per group are allowed and normal; the last one cannot be
 * demoted, removed, or withdraw. What a person may do to any one resource is
 * the other axis entirely ({@code ResourceRole}, the per-resource access list).
 *
 * <p>Declared strongest-first so {@link #atLeast} can compare by {@code ordinal()}.
 */
public enum GroupMemberRole {
    OWNER,
    MEMBER;

    /** True when this rung is at least as privileged as {@code min}. */
    public boolean atLeast(GroupMemberRole min) {
        return ordinal() <= min.ordinal();
    }
}
