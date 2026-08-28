package kr.ac.pusan.pickle.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import kr.ac.pusan.pickle.notice.NoticeAudience;
import org.jspecify.annotations.Nullable;

/**
 * Contract: PATCH /admin/notices/{noticeId} body ({@code minProperties: 1}).
 *
 * <p>Presence-tracked rather than a record, because {@code endsAt} has to be
 * clearable: an omitted field keeps what is stored, while an explicit
 * {@code "endsAt": null} turns a notice that expires into one that does not.
 * Those two are the same value in a record and would be indistinguishable.</p>
 *
 * <p>{@code audience} is editable, and it is the only axis a notice has since
 * V95. Widening one to PUBLIC after the fact is an ordinary edit rather than a
 * privilege escalation: every account that reaches this endpoint could have
 * created the notice PUBLIC in the first place.</p>
 */
public class NoticeUpdateRequest {

    @Size(max = 200, message = "제목은 200자 이하여야 합니다.")
    private String title;
    private boolean titleSet;

    @Size(max = 20000, message = "본문은 20,000자 이하여야 합니다.")
    private String body;
    private boolean bodySet;

    private NoticeAudience audience;
    private boolean audienceSet;

    private Boolean pinned;
    private boolean pinnedSet;

    private Boolean popup;
    private boolean popupSet;

    private Instant startsAt;
    private boolean startsAtSet;

    @Schema(description = "게시 종료 시각. null을 명시하면 만료 없음으로 바꿉니다.")
    private @Nullable Instant endsAt;
    private boolean endsAtSet;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.titleSet = true;
    }

    @Schema(hidden = true)
    public boolean isTitleSet() {
        return titleSet;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
        this.bodySet = true;
    }

    @Schema(hidden = true)
    public boolean isBodySet() {
        return bodySet;
    }

    public NoticeAudience getAudience() {
        return audience;
    }

    public void setAudience(NoticeAudience audience) {
        this.audience = audience;
        this.audienceSet = true;
    }

    @Schema(hidden = true)
    public boolean isAudienceSet() {
        return audienceSet;
    }

    public Boolean getPinned() {
        return pinned;
    }

    public void setPinned(Boolean pinned) {
        this.pinned = pinned;
        this.pinnedSet = true;
    }

    @Schema(hidden = true)
    public boolean isPinnedSet() {
        return pinnedSet;
    }

    public Boolean getPopup() {
        return popup;
    }

    public void setPopup(Boolean popup) {
        this.popup = popup;
        this.popupSet = true;
    }

    @Schema(hidden = true)
    public boolean isPopupSet() {
        return popupSet;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
        this.startsAtSet = true;
    }

    @Schema(hidden = true)
    public boolean isStartsAtSet() {
        return startsAtSet;
    }

    public @Nullable Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(@Nullable Instant endsAt) {
        this.endsAt = endsAt;
        this.endsAtSet = true;
    }

    @Schema(hidden = true)
    public boolean isEndsAtSet() {
        return endsAtSet;
    }

    @Schema(hidden = true)
    public boolean isEmpty() {
        return !titleSet && !bodySet && !audienceSet && !pinnedSet && !popupSet
                && !startsAtSet && !endsAtSet;
    }
}
