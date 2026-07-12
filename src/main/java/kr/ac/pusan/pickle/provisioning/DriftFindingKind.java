package kr.ac.pusan.pickle.provisioning;

/** Drift classes persisted by {@link DriftReconciler} (contract {@code DriftFindingKind}). */
public enum DriftFindingKind {
    /** ① DB VM exists but no matching Proxmox guest. */
    MISSING_IN_PROXMOX,
    /** ② pickle-tagged Proxmox guest nobody in the DB claims. */
    UNMANAGED_GUEST,
    /** ③ granted spec (vcpu/memory) disagrees with the live guest. */
    SPEC_MISMATCH
}
