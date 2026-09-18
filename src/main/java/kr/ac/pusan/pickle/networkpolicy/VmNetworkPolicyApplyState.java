package kr.ac.pusan.pickle.networkpolicy;

/** Desired policy convergence, with a separately proven new-flow barrier failure. */
public enum VmNetworkPolicyApplyState {
    INACTIVE,
    PENDING,
    APPLIED,
    FAILED,
    FAILED_CLOSED
}
