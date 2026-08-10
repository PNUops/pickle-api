package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.notification.NotificationChannel;
import kr.ac.pusan.pickle.notification.NotificationImportance;
import kr.ac.pusan.pickle.notification.NotificationStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminNotificationView}: one delivery-log row —
 * {@code NotificationView} plus the recipient and email-channel state.
 */
public record AdminNotificationResponse(
        UUID id,
        String event,
        String title,
        String body,
        @Nullable String linkPath,
        NotificationImportance importance,
        Instant createdAt,
        @Nullable Instant readAt,
        UUID userId,
        String userEmail,
        NotificationChannel channel,
        NotificationStatus status,
        int attempts,
        @Nullable String lastError,
        @Nullable Instant sentAt) {
}
