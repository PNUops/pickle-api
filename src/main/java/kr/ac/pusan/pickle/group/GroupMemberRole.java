package kr.ac.pusan.pickle.group;

/**
 * Role inside an ownership group (contract schema {@code GroupMemberRole}).
 *
 * <p>Declared strongest-first so {@link #atLeast} can compare by {@code ordinal()}:
 * OWNER &gt; EDITOR &gt; MEMBER &gt; VIEWER.</p>
 */
public enum GroupMemberRole {
    OWNER,
    EDITOR,
    MEMBER,
    VIEWER;

    /**
     * True when this role is at least as privileged as {@code min} (e.g.
     * {@code EDITOR.atLeast(MEMBER)} is true, {@code VIEWER.atLeast(MEMBER)} is
     * false). Relies on the strongest-first declaration order above.
     */
    public boolean atLeast(GroupMemberRole min) {
        return ordinal() <= min.ordinal();
    }
}
