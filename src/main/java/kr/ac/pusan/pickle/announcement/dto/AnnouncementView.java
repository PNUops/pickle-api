package kr.ac.pusan.pickle.announcement.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.announcement.Announcement;
import kr.ac.pusan.pickle.announcement.AnnouncementScope;

/** Contract {@code AnnouncementView}: recipientCount is the send-time snapshot. */
public record AnnouncementView(
        long id,
        String title,
        AnnouncementScope scope,
        Long orgId,
        Long groupId,
        int recipientCount,
        Instant createdAt) {

    public static AnnouncementView from(Announcement announcement) {
        return new AnnouncementView(announcement.getId(), announcement.getTitle(),
                announcement.getScope(), announcement.getOrgId(), announcement.getGroupId(),
                announcement.getRecipientCount(), announcement.getCreatedAt());
    }
}
