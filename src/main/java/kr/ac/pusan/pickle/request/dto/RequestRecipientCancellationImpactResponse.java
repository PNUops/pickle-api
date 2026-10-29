package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import org.jspecify.annotations.Nullable;

@Schema(description = "현재 한 대상자의 생성 대기 취소 영향. 조회는 가입, 큐 접수, 생성이나 취소를 실행하지 않습니다.")
public record RequestRecipientCancellationImpactResponse(UUID requestId, UUID workspaceId, UUID recipientId,
        ResourceType type, RequestRecipientStatus previousStatus, @Nullable String previousReason,
        int previousAttempts, boolean allowed, @Nullable String blockedReason, String fingerprint, Instant observedAt) { }
