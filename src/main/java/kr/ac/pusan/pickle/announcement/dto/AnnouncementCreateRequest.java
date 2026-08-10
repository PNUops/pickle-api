package kr.ac.pusan.pickle.announcement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kr.ac.pusan.pickle.announcement.AnnouncementScope;
import org.jspecify.annotations.Nullable;

/** Contract {@code AnnouncementCreateRequest}. */
public record AnnouncementCreateRequest(
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 200, message = "제목은 200자 이하여야 합니다.")
        String title,
        @NotBlank(message = "본문을 입력해 주세요.")
        @Size(max = 10000, message = "본문은 10,000자 이하여야 합니다.")
        String body,
        @NotNull(message = "공지 범위를 선택해 주세요.")
        AnnouncementScope scope,
        @Nullable UUID orgId,
        @Nullable UUID workspaceId) {
}
