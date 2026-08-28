package kr.ac.pusan.pickle.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.notice.Notice;
import kr.ac.pusan.pickle.notice.NoticeAudience;
import kr.ac.pusan.pickle.notice.NoticeImageMeta;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code AdminNoticeView}: a notice as its author manages it. The
 * admin list is the whole surface — scheduled and expired rows included, and
 * every row carries its body, which is why there is no admin detail read.
 */
public record AdminNoticeView(
        UUID id,
        String title,
        String body,
        NoticeAudience audience,
        boolean pinned,
        boolean popup,
        Instant startsAt,
        @Schema(description = "게시 종료 시각. 비어 있으면 만료되지 않습니다.")
        @Nullable Instant endsAt,
        @Schema(description = "지금 게시 창 안에 있는지. 예정·만료된 공지도 이 목록에는 함께 나옵니다.")
        boolean active,
        @Schema(description = "공지를 등록한 사람의 이름. 계정이 지워졌으면 비어 있습니다.")
        @Nullable String createdByName,
        List<NoticeImageView> images,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminNoticeView from(Notice notice, @Nullable String createdByName,
            List<NoticeImageMeta> images, Instant now) {
        return new AdminNoticeView(notice.getPublicId(), notice.getTitle(), notice.getBody(),
                notice.getAudience(), notice.isPinned(), notice.isPopup(),
                notice.getStartsAt(), notice.getEndsAt(),
                notice.isActiveAt(now), createdByName,
                images.stream().map(image -> NoticeImageView.from(notice.getPublicId(), image))
                        .toList(),
                notice.getCreatedAt(), notice.getUpdatedAt());
    }
}
