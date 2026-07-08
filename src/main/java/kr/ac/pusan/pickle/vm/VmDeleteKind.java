package kr.ac.pusan.pickle.vm;

/** Who initiated a pending VM deletion (docs/plan/03 deletion flows). */
public enum VmDeleteKind {
    /** Student self-delete: hard delete after the grace period. */
    SELF,
    /** Admin-initiated routine delete: scheduled with advance notice. */
    ADMIN,
    /** SYS_ADMIN emergency delete: immediate stop + destroy. */
    EMERGENCY
}
