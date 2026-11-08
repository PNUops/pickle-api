package kr.ac.pusan.pickle.request.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import org.jspecify.annotations.Nullable;

@Schema(description = "대상자 한 명의 생성 대기 취소 영향이에요. 조회로 가입이나 큐 접수, 생성, 취소를 실행하지 않아요.")
public record RequestRecipientCancellationImpactResponse(UUID requestId, UUID workspaceId, UUID recipientId,
        ResourceType type, RequestRecipientStatus previousStatus, @Nullable String previousReason,
        int previousAttempts, boolean allowed, @Nullable String blockedReason, String fingerprint, Instant observedAt) { }
