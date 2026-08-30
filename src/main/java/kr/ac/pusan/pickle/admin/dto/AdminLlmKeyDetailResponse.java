package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import org.jspecify.annotations.Nullable;

/** Secret-free administrator detail for one LLM API key. */
public record AdminLlmKeyDetailResponse(
        UUID id,
        String name,
        @Nullable String purpose,
        LlmApiKeyStatus status,
        @Nullable Instant expiresAt,
        @Nullable Instant lastUsedAt,
        @Nullable Integer rpm,
        @Nullable Integer tpm,
        @Nullable Integer concurrency,
        @Nullable Long dailyTokens,
        boolean quotaExhausted,
        BigDecimal creditLimit,
        @Nullable CreditLimitReset creditLimitReset,
        @Schema(description = "금액 축이 발급되어 현재 연결되어 있는지")
        boolean creditAxisConnected,
        @Nullable UUID workspaceId,
        String workspaceName,
        @Nullable UUID orgId,
        String orgName,
        @Nullable UUID requestId,
        Instant createdAt,
        @Nullable Instant revokedAt) {

    public static AdminLlmKeyDetailResponse from(LlmApiKey key, UUID workspaceId,
            String workspaceName, UUID orgId, String orgName, UUID requestId, Instant now) {
        return new AdminLlmKeyDetailResponse(key.getPublicId(), key.getName(), key.getPurpose(),
                key.effectiveStatus(now), key.getExpiresAt(), key.getLastUsedAt(), key.getRpm(),
                key.getTpm(), key.getConcurrency(), key.getDailyTokens(), key.isQuotaExhausted(),
                key.getCreditLimit(), key.getCreditLimitReset(), key.isCreditAxisConnected(),
                workspaceId, workspaceName, orgId, orgName, requestId, key.getCreatedAt(),
                key.getRevokedAt());
    }
}
