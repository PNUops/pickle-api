package kr.ac.pusan.pickle.publishing;

/**
 * Domain lifecycle (docs/plan/06). Platform subdomains (AUTO/REQUESTED) are
 * ACTIVE on creation; custom domains flow PENDING→VERIFYING→ACTIVE via DNS
 * polling, FAILED on error, REMOVED on delete.
 */
public enum DomainStatus {
    PENDING,
    VERIFYING,
    ACTIVE,
    FAILED,
    REMOVED
}
