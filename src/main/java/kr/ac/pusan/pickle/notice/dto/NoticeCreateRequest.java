package kr.ac.pusan.pickle.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.notice.NoticeAudience;
import kr.ac.pusan.pickle.notice.NoticeScope;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code NoticeCreateRequest}. The cross-field rules the service
 * enforces on top of these: an ORG notice always names its organisation and may
 * never be PUBLIC, a PLATFORM notice may not name one, and an organisation
 * administrator writes only for their own organisation — naming their own is
 * accepted, naming another's is refused rather than corrected.
 */
public record NoticeCreateRequest(
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 200, message = "제목은 200자 이하여야 합니다.")
        String title,
        @NotBlank(message = "본문을 입력해 주세요.")
        @Size(max = 20000, message = "본문은 20,000자 이하여야 합니다.")
        String body,
        @NotNull(message = "공지 범위를 선택해 주세요.")
        NoticeScope scope,
        @Schema(description = "기관 공지의 대상 기관. 기관 공지에는 반드시 있어야 하고 전역 공지에는 넣을 수 없습니다. 기관 관리자는 자기 기관만 지정할 수 있습니다.")
        @Nullable UUID orgId,
        @NotNull(message = "공개 범위를 선택해 주세요.")
        NoticeAudience audience,
        @Schema(description = "목록 상단 고정 여부. 생략하면 고정하지 않습니다.")
        Boolean pinned,
        @Schema(description = "콘솔이 모달로 띄울지. 생략하면 띄우지 않습니다.")
        Boolean popup,
        @Schema(description = "게시 시작 시각. 생략하면 즉시 게시합니다.")
        @Nullable Instant startsAt,
        @Schema(description = "게시 종료 시각. 생략하면 만료되지 않습니다.")
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
