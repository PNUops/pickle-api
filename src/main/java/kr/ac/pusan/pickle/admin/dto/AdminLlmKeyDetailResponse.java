package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
        @Schema(description = "유료 모델이 발급되어 현재 연결되어 있는지")
        boolean creditAxisConnected,
        @Schema(description = "OpenRouter가 마지막으로 보고한 현재 limit window 사용액. 미관측이면 null")
        @Nullable BigDecimal creditUsage,
        @Schema(description = "Key 금액을 vendor에서 관측한 시각. 미관측이면 null")
        @Nullable Instant creditUsageAt,
        @Schema(description = "OpenRouter가 보고한 key 잔여 한도. 미관측 또는 무한도면 null")
        @Nullable BigDecimal creditLimitRemaining,
        @Nullable UUID openrouterAccountId,
        @Nullable String openrouterAccountName,
        @Nullable UUID workspaceId,
        String workspaceName,
        @Nullable UUID orgId,
        String orgName,
        @Nullable UUID requestId,
        Instant createdAt,
        @Nullable Instant revokedAt,
        @Schema(description = "상용(금액) 축에서 이 키가 쓸 수 있는 모델 목록. 빈 배열은 제한 없음")
        List<String> creditAllowedModels) {

    public static AdminLlmKeyDetailResponse from(LlmApiKey key, UUID workspaceId,
            String workspaceName, UUID orgId, String orgName, UUID requestId,
            @Nullable UUID openrouterAccountId, @Nullable String openrouterAccountName,
            List<String> creditAllowedModels, Instant now) {
        return new AdminLlmKeyDetailResponse(key.getPublicId(), key.getName(), key.getPurpose(),
                key.effectiveStatus(now), key.getExpiresAt(), key.getLastUsedAt(), key.getRpm(),
                key.getTpm(), key.getConcurrency(), key.getDailyTokens(), key.isQuotaExhausted(),
                key.getCreditLimit(), key.getCreditLimitReset(), key.isCreditAxisConnected(),
                key.getOpenrouterUsage(), key.getOpenrouterUsageAt(),
                key.getOpenrouterLimitRemaining(), openrouterAccountId, openrouterAccountName,
                workspaceId, workspaceName, orgId, orgName, requestId, key.getCreatedAt(),
                key.getRevokedAt(), creditAllowedModels);
    }
}
