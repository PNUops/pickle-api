package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import org.jspecify.annotations.Nullable;

@Schema(description = "대상자 한 명의 생성 대기 취소 결과예요. 승인과 다른 대상자, 구성원, 초대는 유지돼요.")
public record RequestRecipientCancellationReceipt(UUID id, UUID requestId, UUID workspaceId, UUID recipientId,
        Instant canceledAt, String reason, RequestRecipientStatus previousStatus,
        @Nullable String previousReason, int previousAttempts) { }
