package kr.ac.pusan.pickle.vm;

/**
 * Contract enum {@code VmStatus}. M2 only uses the mock-provisioning
 * transition CREATING → RUNNING; the remaining states land with M3.
 */
public enum VmStatus {
    CREATING,
    RUNNING,
    STOPPED,
    REBOOTING,
    DELETING,
    DELETED,
    ERROR,
    NEEDS_ADMIN
}
