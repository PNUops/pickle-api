package kr.ac.pusan.pickle.vm;

/** Who initiated a pending VM deletion (deletion flows). */
public enum VmDeleteKind {
    /** User self-delete: hard delete after the grace period. */
    SELF,
    /** Admin-initiated routine delete: scheduled with advance notice. */
    ADMIN,
    /** SYS_ADMIN force delete: immediate stop + destroy. */
    FORCE
}
