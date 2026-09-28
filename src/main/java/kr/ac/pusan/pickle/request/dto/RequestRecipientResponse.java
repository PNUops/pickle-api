package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import org.jspecify.annotations.Nullable;

/** One recipient of a many-person request, as its request detail shows it. */
public record RequestRecipientResponse(
        UUID id,
        @Schema(description = "대상 계정. 가입 전인 초대 대상자는 null입니다.")
        @Nullable UUID userId,
        @Schema(description = "대상 계정의 이름. 가입 전인 초대 대상자는 null입니다.")
        @Nullable String name,
        @Schema(description = "초대할 때 적은 이메일 또는 학번. 신청자, 워크스페이스 소유자, 승인 권한이 있는 관리자에게만 보입니다.")
        @Nullable String invitee,
        RequestRecipientStatus status,
        @Schema(description = "만든 리소스. 종류는 신청의 type을 따릅니다. 만들기 전에는 null입니다.")
        @Nullable UUID resourceId,
        @Schema(description = "만들지 않았거나 실패한 이유.")
        @Nullable String reason) {
}
