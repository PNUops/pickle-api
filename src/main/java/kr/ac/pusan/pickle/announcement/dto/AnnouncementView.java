package kr.ac.pusan.pickle.announcement.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.announcement.Announcement;
import kr.ac.pusan.pickle.announcement.AnnouncementScope;
import org.jspecify.annotations.Nullable;

/** Contract {@code AnnouncementView}: recipientCount is the send-time snapshot. */
public record AnnouncementView(
        long id,
        String title,
        AnnouncementScope scope,
        @Nullable Long orgId,
        @Nullable Long workspaceId,
        int recipientCount,
        Instant createdAt) {

    public static AnnouncementView from(Announcement announcement) {
        return new AnnouncementView(announcement.getId(), announcement.getTitle(),
                announcement.getScope(), announcement.getOrgId(), announcement.getWorkspaceId(),
                announcement.getRecipientCount(), announcement.getCreatedAt());
    }
}
