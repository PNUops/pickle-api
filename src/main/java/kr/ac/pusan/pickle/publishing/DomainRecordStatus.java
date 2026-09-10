package kr.ac.pusan.pickle.publishing;

/**
 * How far a record set has got toward the state its owner asked for.
 *
 * <p>The same shape the route's status has, and for the same reason: the write
 * happens outside the transaction that accepted the edit and can fail on its
 * own, so the row has to remember where it stands. REMOVED is a set on its way
 * out of the zone rather than one already gone — the row survives until the
 * provider confirms the deletion, which is what keeps a failed removal
 * retrying instead of being forgotten.
 */
public enum DomainRecordStatus {
    PENDING,
    APPLIED,
    FAILED,
    REMOVED
}
