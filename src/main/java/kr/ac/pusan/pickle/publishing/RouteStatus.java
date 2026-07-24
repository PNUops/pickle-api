package kr.ac.pusan.pickle.publishing;

/**
 * Route (publish) application status. PENDING (accepted, awaiting
 * apply) → APPLIED (proxy-agent confirmed) / FAILED (see lastError); REMOVED on
 * unpublish.
 */
public enum RouteStatus {
    PENDING,
    APPLIED,
    FAILED,
    REMOVED
}
