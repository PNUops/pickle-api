package kr.ac.pusan.pickle.access;

/**
 * What one person may do to one resource (contract schema {@code ResourceRole}).
 *
 * <p>This is the resource axis, deliberately its own type rather than a reuse of
 * the workspace ladder: a container or an API key has no workspace-level meaning, and
 * the workspace axis has no notion of an editor. The four rungs read the same way
 * for every resource kind — VIEWER sees it, MEMBER uses it for its purpose,
 * EDITOR changes how it is configured, OWNER decides who reaches it and may
 * destroy it — while what each rung concretely permits is per-kind.
 *
 * <p>Declared strongest-first so {@link #atLeast} can compare by {@code ordinal()}:
 * OWNER &gt; EDITOR &gt; MEMBER &gt; VIEWER.
 */
public enum ResourceRole {
    OWNER,
    EDITOR,
    MEMBER,
    VIEWER;

    /**
     * True when this rung is at least as privileged as {@code min} (e.g.
     * {@code EDITOR.atLeast(MEMBER)} is true, {@code VIEWER.atLeast(MEMBER)} is
     * false). Relies on the strongest-first declaration order above.
     */
    public boolean atLeast(ResourceRole min) {
        return ordinal() <= min.ordinal();
    }
}
