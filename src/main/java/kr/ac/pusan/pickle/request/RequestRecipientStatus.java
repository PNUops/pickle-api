package kr.ac.pusan.pickle.request;

/**
 * Where one recipient of a many-person request stands (contract schema
 * {@code RequestRecipientStatus}).
 *
 * <p>{@code CREATING} is part of the published set but is not written today:
 * each recipient's resource is created in a single transaction, so the row
 * goes straight from {@code QUEUED} to {@code CREATED} or {@code FAILED}. A
 * VM's own provisioning is tracked by the VM, not by this row.</p>
 */
public enum RequestRecipientStatus {
    PENDING_JOIN,
    QUEUED,
    CREATING,
    CREATED,
    SKIPPED_EXPIRED,
    SKIPPED_INELIGIBLE,
    FAILED,
    CANCELED
}
