package kr.ac.pusan.pickle.notification;

/**
 * Email state: PENDING is normal/bundle/retry waiting, SENDING owns one active
 * SMTP attempt, and SENT means SMTP handoff. Confirmed rejection can exhaust
 * the retry budget as FAILED; UNKNOWN is never automatically replayed.
 * SKIPPED records channel selection or legacy inactive-recipient exclusions.
 */
public enum NotificationStatus {
    PENDING, SENDING, SENT, FAILED, SKIPPED, UNKNOWN
}
