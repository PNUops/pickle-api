package kr.ac.pusan.pickle.provisioning;

/**
 * User-visible task state (docs/plan/02; the console renders it directly).
 * PENDING/RUNNING/RETRYING/NEEDS_ADMIN count as "live" — the partial unique
 * index on (vm_id, kind) allows at most one live task per VM and kind.
 */
public enum ProvisioningTaskStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    RETRYING,
    NEEDS_ADMIN;

    private static final java.util.Set<ProvisioningTaskStatus> LIVE =
            java.util.Set.of(PENDING, RUNNING, RETRYING, NEEDS_ADMIN);

    /** The "live" statuses — a VM with a live task is owned by the pipeline. */
    public static java.util.Set<ProvisioningTaskStatus> live() {
        return LIVE;
    }
}
