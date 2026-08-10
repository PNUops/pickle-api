package kr.ac.pusan.pickle.announcement.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.announcement.Announcement;
import kr.ac.pusan.pickle.announcement.AnnouncementScope;
import org.jspecify.annotations.Nullable;

/** Contract {@code AnnouncementView}: recipientCount is the send-time snapshot. */
public record AnnouncementView(
        UUID id,
        String title,
        AnnouncementScope scope,
        @Nullable UUID orgId,
        @Nullable UUID workspaceId,
        int recipientCount,
        Instant createdAt) {

    public static AnnouncementView from(Announcement announcement, UUID orgId, UUID workspaceId) {
        return new AnnouncementView(announcement.getPublicId(), announcement.getTitle(),
                announcement.getScope(), orgId, workspaceId,
                announcement.getRecipientCount(), announcement.getCreatedAt());
    }
}
