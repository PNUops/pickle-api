package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import org.jspecify.annotations.Nullable;

/** Secret-free administrator list row for one LLM API key. */
public record AdminLlmKeySummaryResponse(
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
        @Nullable BigDecimal creditLimit,
        @Nullable CreditLimitReset creditLimitReset,
        @Schema(description = "유료 모델이 발급되어 현재 연결되어 있는지")
        @Nullable Boolean creditAxisConnected,
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
        @Schema(description = "개별 키 금융 정보의 현재 열람 권한") boolean financeVisible,
        @Schema(description = "키 metadata의 현재 열람 권한") boolean metadataVisible,
        @Schema(description = "키 사용 관측의 현재 열람 권한") boolean observationVisible, List<String> allowedWorkIds) {

    public static AdminLlmKeySummaryResponse from(LlmApiKey key, UUID workspaceId,
            String workspaceName, UUID orgId, String orgName, UUID requestId,
            @Nullable UUID openrouterAccountId, @Nullable String openrouterAccountName,
            Instant now) {
        return new AdminLlmKeySummaryResponse(key.getPublicId(), key.getName(), key.getPurpose(),
                key.effectiveStatus(now), key.getExpiresAt(), key.getLastUsedAt(), key.getRpm(),
                key.getTpm(), key.getConcurrency(), key.getDailyTokens(), key.getCreditLimit(),
                key.getCreditLimitReset(), key.isCreditAxisConnected(),
                key.getOpenrouterUsage(), key.getOpenrouterUsageAt(),
                key.getOpenrouterLimitRemaining(), openrouterAccountId,
                openrouterAccountName, workspaceId, workspaceName, orgId, orgName,
                requestId, key.getCreatedAt(), true, true, true, List.of());
    }

    public AdminLlmKeySummaryResponse projectFinance(boolean visible) {
        if (visible) { return this; }
        return new AdminLlmKeySummaryResponse(id, name, purpose, status, expiresAt, lastUsedAt, rpm, tpm, concurrency, dailyTokens, null, null, null, null, null, null, openrouterAccountId, openrouterAccountName, workspaceId, workspaceName, orgId, orgName, requestId, createdAt, false, metadataVisible, observationVisible, allowedWorkIds);
    }

    public AdminLlmKeySummaryResponse project(boolean metadata, boolean observation, boolean finance) {
        AdminLlmKeySummaryResponse value = projectFinance(finance);
        return new AdminLlmKeySummaryResponse(value.id, value.name, metadata ? value.purpose : null, value.status, value.expiresAt, (metadata || observation) ? value.lastUsedAt : null, (metadata || observation) ? value.rpm : null, (metadata || observation) ? value.tpm : null, (metadata || observation) ? value.concurrency : null, (metadata || observation) ? value.dailyTokens : null, value.creditLimit, value.creditLimitReset, value.creditAxisConnected, value.creditUsage, value.creditUsageAt, value.creditLimitRemaining, (metadata || finance) ? value.openrouterAccountId : null, (metadata || finance) ? value.openrouterAccountName : null, value.workspaceId, value.workspaceName, value.orgId, value.orgName, value.requestId, value.createdAt, value.financeVisible, metadata, observation, allowedWorkIds);
    }
    public AdminLlmKeySummaryResponse withAllowedWorkIds(List<String> work) {
        return new AdminLlmKeySummaryResponse(id,name,purpose,status,expiresAt,lastUsedAt,rpm,tpm,concurrency,dailyTokens,
                creditLimit,creditLimitReset,creditAxisConnected,creditUsage,creditUsageAt,creditLimitRemaining,openrouterAccountId,
                openrouterAccountName,workspaceId,workspaceName,orgId,orgName,requestId,createdAt,financeVisible,metadataVisible,observationVisible,List.copyOf(work));
    }
}
