package kr.ac.pusan.pickle.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.notice.Notice;
import kr.ac.pusan.pickle.notice.NoticeAudience;
import kr.ac.pusan.pickle.notice.NoticeImageMeta;
import kr.ac.pusan.pickle.notice.NoticeScope;
import kr.ac.pusan.pickle.orgs.Org;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code NoticeView}: a notice as a reader sees it. Every row this
 * carries is already inside its active window and already cleared for the
 * caller — the list and the detail return the same shape, body included, so a
 * landing page renders from one call.
 */
public record NoticeView(
        UUID id,
        String title,
        String body,
        NoticeScope scope,
        @Schema(description = "기관 공지가 속한 기관. 전역 공지에서는 비어 있습니다.")
        @Nullable UUID orgId,
        @Schema(description = "그 기관의 이름. 전역 공지에서는 비어 있습니다.")
        @Nullable String orgName,
        NoticeAudience audience,
        @Schema(description = "목록 상단 고정 여부")
        boolean pinned,
        @Schema(description = "콘솔이 모달로 띄울 공지인지")
        boolean popup,
        Instant startsAt,
        @Schema(description = "게시 종료 시각. 비어 있으면 만료되지 않습니다.")
        @Nullable Instant endsAt,
        List<NoticeImageView> images,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * The organisation is carried flat as an id plus a name, not as a nested
     * object: an id to link by and a name to show, matching how every other
     * reference in this API is shaped.
     */
    public static NoticeView from(Notice notice, @Nullable Org org,
            List<NoticeImageMeta> images) {
        return new NoticeView(notice.getPublicId(), notice.getTitle(), notice.getBody(),
                notice.getScope(), org == null ? null : org.getPublicId(),
                org == null ? null : org.getName(), notice.getAudience(), notice.isPinned(),
                notice.isPopup(), notice.getStartsAt(), notice.getEndsAt(),
                images.stream().map(image -> NoticeImageView.from(notice.getPublicId(), image))
                        .toList(),
                notice.getCreatedAt(), notice.getUpdatedAt());
    }
}
