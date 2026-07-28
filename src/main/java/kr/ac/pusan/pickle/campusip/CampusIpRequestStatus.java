package kr.ac.pusan.pickle.campusip;

/**
 * 교내 IP request lifecycle. Legal transitions (enforced in the services):
 * REQUESTED → APPROVED | REJECTED, APPROVED → GRANTED | REJECTED,
 * GRANTED → REVOKED. REQUESTED/APPROVED/GRANTED count as live — at most one
 * live request per VM (partial unique index).
 */
public enum CampusIpRequestStatus {
    REQUESTED,
    APPROVED,
    GRANTED,
    REJECTED,
    REVOKED
}
