package kr.ac.pusan.pickle.provisioning;

/**
 * Drift classes (contract {@code DriftFindingKind}). The Proxmox kinds are
 * produced by {@link DriftReconciler}; the OpenRouter kinds by the LLM key
 * reconciler — one producer per kind, because the shared auto-resolve works
 * per kind and two producers of one kind would resolve each other's findings.
 */
public enum DriftFindingKind {
    /** ① DB VM exists but no matching Proxmox guest. */
    MISSING_IN_PROXMOX,
    /** ② pickle-tagged Proxmox guest nobody in the DB claims. */
    UNMANAGED_GUEST,
    /** ③ granted spec (vcpu/memory) disagrees with the live guest. */
    SPEC_MISMATCH,
    /** ④ OpenRouter holds a runtime key our table does not explain. */
    OPENROUTER_ORPHAN,
    /** ⑤ the two halves of a funded key disagree about being alive. */
    OPENROUTER_STALE
}
