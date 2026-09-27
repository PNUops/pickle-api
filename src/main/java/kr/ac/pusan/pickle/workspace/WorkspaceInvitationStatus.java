package kr.ac.pusan.pickle.workspace;

/** Lifecycle of a {@link WorkspaceInvitation}; the database check lists the same three values. */
public enum WorkspaceInvitationStatus {
    PENDING,
    /** A membership exists because of it, or already existed when it was claimed. */
    ACCEPTED,
    CANCELED
}
