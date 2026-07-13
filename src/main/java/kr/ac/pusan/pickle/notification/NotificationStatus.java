package kr.ac.pusan.pickle.notification;

/**
 * Email delivery state ({@code notification_status} PG enum, contract
 * {@code NotificationDeliveryStatus}): PENDING → SENT, or FAILED after the
 * retry budget; SKIPPED = the event's email channel is off.
 */
public enum NotificationStatus {
    PENDING, SENT, FAILED, SKIPPED
}
