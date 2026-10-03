package kr.ac.pusan.pickle.announcement.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.announcement.Announcement;
import kr.ac.pusan.pickle.announcement.AnnouncementScope;
import org.jspecify.annotations.Nullable;

/** Saved send content and scope; recipientCount is never recalculated from current membership. */
public record AnnouncementDetailResponse(UUID id, String title, String body, AnnouncementScope scope,
        @Nullable UUID orgId, @Nullable UUID workspaceId, int recipientCount, Instant createdAt) {

    public static AnnouncementDetailResponse from(Announcement announcement, UUID orgId, UUID workspaceId) {
        return new AnnouncementDetailResponse(announcement.getPublicId(), announcement.getTitle(), announcement.getBody(),
                announcement.getScope(), orgId, workspaceId, announcement.getRecipientCount(), announcement.getCreatedAt());
    }
}
