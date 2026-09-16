package kr.ac.pusan.pickle.notification;

/**
 * Email delivery state ({@code notification_status} PG enum, contract
 * {@code NotificationDeliveryStatus}): PENDING → SENT, or FAILED after the
 * retry budget; SKIPPED = the recipient was no longer active when the
 * dispatcher reached the row. There is no per-event email channel switch —
 * every event is mailed.
 */
public enum NotificationStatus {
    PENDING, SENT, FAILED, SKIPPED
}
