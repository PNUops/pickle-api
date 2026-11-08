package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import org.jspecify.annotations.Nullable;

/** One recipient of a many-person request, as its request detail shows it. */
public record RequestRecipientResponse(
        UUID id,
        @Schema(description = "대상 계정이에요. 가입 전인 초대 대상자는 null이에요.")
        @Nullable UUID userId,
        @Schema(description = "대상 계정의 이름이에요. 가입 전인 초대 대상자는 null이에요.")
        @Nullable String name,
        @Schema(description = "초대할 때 적은 이메일 또는 학번이에요. 신청자, 워크스페이스 소유자 또는 해당 신청의 조회가 허용된 담당자에게 보여요.")
        @Nullable String invitee,
        RequestRecipientStatus status,
        @Schema(description = "만든 리소스예요. 종류는 신청의 type을 따라요. 만들기 전에는 null이에요.")
        @Nullable UUID resourceId,
        @Schema(description = "기존 실패나 자동 종료 이유예요. 개별 생성 대기 취소 사유는 cancellation.reason으로 구분하며 이전 실패 이력은 보존해요.")
        @Nullable String reason,
        RequestRecipientCancellationCapability cancellationCapability,
        @Nullable RequestRecipientCancellationReceipt cancellation) {
}
