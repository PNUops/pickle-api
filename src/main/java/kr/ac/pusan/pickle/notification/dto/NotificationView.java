package kr.ac.pusan.pickle.notification.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.notification.Notification;
import kr.ac.pusan.pickle.notification.NotificationImportance;
import org.jspecify.annotations.Nullable;

/** Contract {@code NotificationView}: one in-app notification (own rows only). */
public record NotificationView(
        long id,
        String event,
        String title,
        String body,
        @Nullable String linkPath,
        NotificationImportance importance,
        Instant createdAt,
        @Nullable Instant readAt) {

    public static NotificationView from(Notification notification) {
        return new NotificationView(notification.getId(), notification.getEvent(),
                notification.getTitle(), notification.getBody(), notification.getLinkPath(),
                notification.getImportance(), notification.getCreatedAt(),
                notification.getReadAt());
    }
}
