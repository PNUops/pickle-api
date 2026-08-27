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
     * Performed through an administrator surface, or by an administrator using
     * the override the member-facing delete path grants them. The member-facing
     * history reports these without the administrator's identity; who exactly
     * acted is in the audit log, which only administrators read.
     */
    ADMIN,
    /**
     * A human acted and which surface they came through is <b>not known</b> —
     * the row predates this column, or it was written by a path that could not
     * tell (a power job queued before the surface rode along with it).
     *
     * <p>It exists because the alternative is a guess, and a guess here is
     * written into a permanent history: naming the actor could publish an
     * administrator, and calling it an intervention could accuse a colleague of
     * one. Both surfaces render it the way this history read before there was
     * anything better — a human acted, unnamed. Nothing writes it deliberately.
     */
    UNKNOWN
}
