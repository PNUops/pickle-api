package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record RequestRecipientCancellationCapability(
        @Schema(description = "일반 신청 경로에서 현재 실제 OWNER로 취소할 수 있는지") boolean ownerAllowed,
        @Schema(description = "관리자 지원 경로에서 기존 지원 쓰기 권한으로 취소할 수 있는지") boolean supportAllowed,
        @Schema(description = "취소할 수 없는 이유예요. 이미 등록된 자원은 기존 상세의 삭제 또는 폐기 경로를 확인해요.") @Nullable String blockedReason) { }
