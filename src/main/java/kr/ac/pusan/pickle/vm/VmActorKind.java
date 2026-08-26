package kr.ac.pusan.pickle.vm;

/**
 * Which surface an entry in the permanent per-VM history came through
 * (contract schema {@code VmActorKind}), stamped when the row is written.
 *
 * <p>Deliberately not the actor's role: an account's effective role is the
 * highest one it holds anywhere, so an operator doing ordinary work in their
 * own workspace would read as an intervention. The question this answers is
 * which endpoint performed the action, which is fixed at that moment and stays
 * true afterwards.
 */
public enum VmActorKind {
    /** Background job or sweeper — no human actor, so {@code actorId} is null. */
    SYSTEM,
    /** Performed through the member-facing surface by someone who holds the VM. */
    MEMBER,
    /**
     * Performed through an administrator surface ({@code /admin/...}). The
     * member-facing history reports these without the administrator's
     * identity; who exactly acted is in the audit log, which only
     * administrators read.
     */
    ADMIN
}
