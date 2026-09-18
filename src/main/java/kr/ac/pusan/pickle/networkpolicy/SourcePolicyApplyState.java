package kr.ac.pusan.pickle.networkpolicy;

/** Whether the saved policy is inactive, waiting, confirmed, or rejected. */
public enum SourcePolicyApplyState {
    INACTIVE,
    PENDING,
    APPLIED,
    FAILED
}
