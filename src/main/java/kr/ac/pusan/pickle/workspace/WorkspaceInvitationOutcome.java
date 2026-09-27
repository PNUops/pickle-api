package kr.ac.pusan.pickle.workspace;

/** What one entry of a bulk invitation came to. */
public enum WorkspaceInvitationOutcome {
    /** An ACTIVE account matched and is now a member. */
    ADDED,
    /** No ACTIVE account matched; an open invitation now waits for one. */
    INVITED,
    ALREADY_MEMBER,
    ALREADY_INVITED,
    /** The same person appeared earlier in the same request. */
    DUPLICATE_IN_REQUEST
}
