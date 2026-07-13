package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.notification.NotificationChannel;
import kr.ac.pusan.pickle.notification.NotificationImportance;
import kr.ac.pusan.pickle.notification.NotificationStatus;

/**
 * Contract schema {@code AdminNotificationView}: one delivery-log row —
 * {@code NotificationView} plus the recipient and email-channel state.
 */
public record AdminNotificationResponse(
        Long id,
        String event,
        String title,
        String body,
        String linkPath,
        NotificationImportance importance,
        Instant createdAt,
        Instant readAt,
        Long userId,
        String userEmail,
        NotificationChannel channel,
        NotificationStatus status,
        int attempts,
        String lastError,
        Instant sentAt) {
}
