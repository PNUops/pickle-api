package kr.ac.pusan.pickle.workspace;

/**
 * The workspace kinds a request may name (contract schema
 * {@code CreatableWorkspaceKind}).
 *
 * <p>A separate top-level enum rather than a narrowing annotation on
 * {@link WorkspaceKind}: springdoc publishes a top-level enum as its own schema
 * and the request field as a {@code $ref} to it, whereas a sibling
 * {@code enum} beside a {@code $ref} is dropped by openapi-typescript — which
 * is how the console's generated type came to accept values the server refuses.
 *
 * <p>Two kinds are missing on purpose. {@link WorkspaceKind#PERSONAL} is
 * created at signup and cannot be asked for, and {@link WorkspaceKind#TEAM} is
 * retired.
 */
public enum CreatableWorkspaceKind {
    PROJECT,
    COURSE,
    PROGRAM,
    LAB,
    CLUB,
    COMPETITION,
    STUDY;

    public WorkspaceKind toWorkspaceKind() {
        return WorkspaceKind.valueOf(name());
    }
}
