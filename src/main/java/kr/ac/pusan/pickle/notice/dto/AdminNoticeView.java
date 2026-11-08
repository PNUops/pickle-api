package kr.ac.pusan.pickle.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.notice.Notice;
import kr.ac.pusan.pickle.notice.NoticeImageMeta;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code AdminNoticeView}: a notice as its author manages it. The
 * admin list and detail include scheduled and expired documents. The detail
 * remains addressable when publication changes move a document off a page.
 */
public record AdminNoticeView(
        UUID id,
        String title,
        String body,
        @Schema(description = "목록 상단 고정 여부")
        boolean pinned,
        @Schema(description = "화면을 막지 않는 카드로 띄울 공지인지 나타내요. 켜면 게시 기간 동안 로그인 없이도 읽을 수 있어요.")
        boolean popup,
        Instant startsAt,
        @Schema(description = "게시 종료 시각이며, null이면 만료되지 않아요.")
        @Nullable Instant endsAt,
        @Schema(description = "현재 게시 기간 안에 있는지 나타내요. 관리 화면에는 게시 예정이거나 종료된 공지도 나와요.")
        boolean active,
        @Schema(description = "공지를 등록한 사람의 이름이며, 계정이 삭제됐으면 null이에요.")
        @Nullable String createdByName,
        List<NoticeImageView> images,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminNoticeView from(Notice notice, @Nullable String createdByName,
            List<NoticeImageMeta> images, Instant now) {
        return new AdminNoticeView(notice.getPublicId(), notice.getTitle(), notice.getBody(),
                notice.isPinned(), notice.isPopup(), notice.getStartsAt(), notice.getEndsAt(),
                notice.isActiveAt(now), createdByName,
                images.stream().map(image -> NoticeImageView.from(notice.getPublicId(), image))
                        .toList(),
                notice.getCreatedAt(), notice.getUpdatedAt());
    }
}
