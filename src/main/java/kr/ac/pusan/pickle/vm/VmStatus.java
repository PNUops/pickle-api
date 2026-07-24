package kr.ac.pusan.pickle.vm;

/**
 * Contract enum {@code VmStatus}. Early builds only used the mock-provisioning
 * transition CREATING → RUNNING; the remaining states land with the real
 * pipeline.
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
