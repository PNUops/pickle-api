package kr.ac.pusan.pickle.announcement.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.announcement.AnnouncementScope;
import org.jspecify.annotations.Nullable;

/** Current recipient estimate; it neither reserves nor freezes a future send. */
public record AnnouncementPreviewResponse(AnnouncementScope scope, @Nullable UUID orgId,
        @Nullable UUID workspaceId, int recipientCount, Instant observedAt,
        List<AnnouncementRecipientSample> sample, boolean truncated, List<String> warnings) {

    public record AnnouncementRecipientSample(UUID userId, String name, String email) { }
}
