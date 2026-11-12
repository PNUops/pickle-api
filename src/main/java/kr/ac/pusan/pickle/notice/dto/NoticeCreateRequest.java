package kr.ac.pusan.pickle.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code NoticeCreateRequest}. Every notice is platform-wide, so the
 * only visibility choice the author makes is {@code popup}: a popup notice is
 * shown as a non-blocking card and is readable without a session, and anything else sits
 * on the board for signed-in readers. Omitting it means neither, which is the
 * closed default. The one cross-field rule the service adds is that a
 * publication window must end after it starts.
 */
public record NoticeCreateRequest(
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 200, message = "제목은 200자 이내로 입력해 주세요.")
        String title,
        @NotBlank(message = "본문을 입력해 주세요.")
        @Size(max = 20000, message = "본문은 20,000자 이내로 입력해 주세요.")
        String body,
        @Schema(description = "목록 상단 고정 여부이며, 생략하면 고정하지 않아요.")
        Boolean pinned,
        @Schema(description = "화면을 막지 않는 카드로 띄울지 나타내요. 켜면 게시 기간 동안 로그인하지 않은 방문자도 읽을 수 있어요."
                + " 생략하면 카드를 띄우지 않고 비로그인 방문자에게도 공개하지 않아요.")
        Boolean popup,
        @Schema(description = "게시 시작 시각이며, 생략하면 현재 시각부터 게시해요.")
        @Nullable Instant startsAt,
        @Schema(description = "게시 종료 시각이며, 생략하면 만료되지 않아요.")
        @Nullable Instant endsAt) {

    /** Boxed so an omitted flag means "no", the way the other request bodies read theirs. */
    public boolean isPinned() {
        return Boolean.TRUE.equals(pinned);
    }

    /** Boxed so an omitted flag means "no", the way the other request bodies read theirs. */
    public boolean isPopup() {
        return Boolean.TRUE.equals(popup);
    }
}
