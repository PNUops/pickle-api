package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCredentialError;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCredentialStatus;
import org.jspecify.annotations.Nullable;

/** Secret-free lifecycle metadata. No credential identity or fragment is exposed. */
public record OpenRouterCredentialStateResponse(
        @Schema(description = "Credential lifecycle 상태")
        OpenRouterCredentialStatus status,
        @Schema(description = "등록 시각")
        Instant createdAt,
        @Schema(description = "마지막 검증 시도 시각. 시도 전이면 null")
        @Nullable Instant lastVerificationAttemptAt,
        @Schema(description = "마지막 검증 성공 시각. 성공 이력이 없으면 null")
        @Nullable Instant verifiedAt,
        @Schema(description = "ACTIVE 전환 시각. 활성화 전이면 null")
        @Nullable Instant activatedAt,
        @Schema(description = "RETIRING 전환 시각. 해당 상태가 아니면 null")
        @Nullable Instant retiringAt,
        @Schema(description = "Management API 호출에 마지막으로 사용한 시각. 사용 전이면 null")
        @Nullable Instant lastUsedAt,
        @Schema(description = "이 credential로 key reconciliation이 마지막으로 성공한 시각. 성공 전이면 null")
        @Nullable Instant lastReconciledAt,
        @Schema(description = "마지막 검증 오류 분류. 최근 검증이 성공했으면 null")
        @Nullable OpenRouterCredentialError verificationError,
        @Schema(description = "RETIRING 상태가 24시간을 넘었는지")
        boolean retiringOverdue) {
}
