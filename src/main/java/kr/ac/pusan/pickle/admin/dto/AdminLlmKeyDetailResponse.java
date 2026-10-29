package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.llm.PassthroughEndpoints;
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
        @Nullable Boolean quotaExhausted,
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
        @Nullable Instant revokedAt,
        @Schema(description = "이 키가 쓸 수 있는 유료 모델 목록. 빈 배열은 제한 없음"
                + CreditModelPatterns.ALLOW_PATTERN_DESCRIPTION)
        @Nullable List<String> creditAllowedModels,
        @Schema(description = "이 키가 쓸 수 없는 유료 모델 목록. 빈 배열은 차단 없음. "
                + "허용 목록과 함께 걸리면 차단이 이긴다."
                + CreditModelPatterns.DENY_PATTERN_DESCRIPTION)
        @Nullable List<String> creditDeniedModels,
        @ArraySchema(schema = @Schema(allowableValues = {PassthroughEndpoints.CHAT,
                PassthroughEndpoints.WEB_SEARCH, PassthroughEndpoints.PDF_OCR,
                PassthroughEndpoints.CONTAINERS, PassthroughEndpoints.IMAGES,
                PassthroughEndpoints.EMBEDDINGS, PassthroughEndpoints.RERANK,
                PassthroughEndpoints.SPEECH, PassthroughEndpoints.TRANSCRIPTIONS,
                PassthroughEndpoints.VIDEOS, PassthroughEndpoints.BATCHES,
                PassthroughEndpoints.FILES}),
                arraySchema = @Schema(description = "이 키에 부여된 기능 권한. 빈 배열은 "
                        + "부여 없음(chat 포함). 모델 목록 둘과 반대로 채워야 열린다."))
        @Nullable List<String> passthroughEndpoints,
        @Schema(description = "자체 서빙을 포함한 chat 기능 허용 상태") @Nullable Boolean chatEnabled,
        @Schema(description = "개별 키 금융 정보의 현재 열람 권한") boolean financeVisible,
        @Schema(description = "키 metadata의 현재 열람 권한") boolean metadataVisible,
        @Schema(description = "키 사용 관측의 현재 열람 권한") boolean observationVisible, @Nullable List<InstitutionLlmAccountReference> accountReferences,
        boolean tokenReferenceVisible, boolean budgetReferenceVisible, List<String> allowedWorkIds,
        @Schema(description = "현재 예산 명령의 최초 사업 계정 결합 가능 여부. 예산 명령 참조가 없으면 null이며 사용액 관측값을 제공하지 않습니다.")
        @Nullable Boolean initialAccountBindingAllowed, @Nullable LlmKeyLimitsProjection limitsProjection) {

    public static AdminLlmKeyDetailResponse from(LlmApiKey key, UUID workspaceId,
            String workspaceName, UUID orgId, String orgName, UUID requestId,
            @Nullable UUID openrouterAccountId, @Nullable String openrouterAccountName,
            List<String> creditAllowedModels, List<String> creditDeniedModels,
            List<String> passthroughEndpoints, Instant now) {
        return new AdminLlmKeyDetailResponse(key.getPublicId(), key.getName(), key.getPurpose(),
                key.effectiveStatus(now), key.getExpiresAt(), key.getLastUsedAt(), key.getRpm(),
                key.getTpm(), key.getConcurrency(), key.getDailyTokens(), key.isQuotaExhausted(),
                key.getCreditLimit(), key.getCreditLimitReset(), key.isCreditAxisConnected(),
                key.getOpenrouterUsage(), key.getOpenrouterUsageAt(),
                key.getOpenrouterLimitRemaining(), openrouterAccountId, openrouterAccountName,
                workspaceId, workspaceName, orgId, orgName, requestId, key.getCreatedAt(),
                key.getRevokedAt(), creditAllowedModels, creditDeniedModels,
                passthroughEndpoints, passthroughEndpoints.contains(PassthroughEndpoints.CHAT), true, true, true, null, false, false, List.of(), null, null);
    }

    public AdminLlmKeyDetailResponse project(boolean metadata, boolean observation, boolean finance, boolean tokenReference, boolean budgetReference) {
        boolean token = metadata || observation || tokenReference;
        boolean budget = finance || budgetReference;
        return new AdminLlmKeyDetailResponse(id, name, metadata ? purpose : null, status, expiresAt,
                metadata || observation ? lastUsedAt : null, token ? rpm : null, token ? tpm : null, token ? concurrency : null,
                token ? dailyTokens : null, metadata || observation ? quotaExhausted : null,
                budget ? creditLimit : null, budget ? creditLimitReset : null, finance ? creditAxisConnected : null,
                finance ? creditUsage : null, finance ? creditUsageAt : null, finance ? creditLimitRemaining : null,
                metadata || budget ? openrouterAccountId : null, metadata || budget ? openrouterAccountName : null,
                workspaceId, workspaceName, orgId, orgName, requestId, createdAt, revokedAt,
                budget ? creditAllowedModels : null, budget ? creditDeniedModels : null, budget ? passthroughEndpoints : null,
                token || budget ? chatEnabled : null, finance, metadata, observation, accountReferences, tokenReference, budgetReference, allowedWorkIds, initialAccountBindingAllowed,
                !budget || limitsProjection == null ? null : finance && observation
                        ? limitsProjection : limitsProjection.withoutAccountImpact());
    }

    public AdminLlmKeyDetailResponse withAccountReferences(@Nullable List<InstitutionLlmAccountReference> references) {
        return new AdminLlmKeyDetailResponse(id, name, purpose, status, expiresAt, lastUsedAt, rpm, tpm, concurrency, dailyTokens, quotaExhausted, creditLimit, creditLimitReset, creditAxisConnected, creditUsage, creditUsageAt, creditLimitRemaining, openrouterAccountId, openrouterAccountName, workspaceId, workspaceName, orgId, orgName, requestId, createdAt, revokedAt, creditAllowedModels, creditDeniedModels, passthroughEndpoints, chatEnabled, financeVisible, metadataVisible, observationVisible, references, tokenReferenceVisible, budgetReferenceVisible, allowedWorkIds, initialAccountBindingAllowed, limitsProjection);
    }
    public AdminLlmKeyDetailResponse withAllowedWorkIds(List<String> work) {
        return new AdminLlmKeyDetailResponse(id,name,purpose,status,expiresAt,lastUsedAt,rpm,tpm,concurrency,dailyTokens,quotaExhausted,
                creditLimit,creditLimitReset,creditAxisConnected,creditUsage,creditUsageAt,creditLimitRemaining,openrouterAccountId,
                openrouterAccountName,workspaceId,workspaceName,orgId,orgName,requestId,createdAt,revokedAt,creditAllowedModels,
                creditDeniedModels,passthroughEndpoints,chatEnabled,financeVisible,metadataVisible,observationVisible,accountReferences,
                tokenReferenceVisible,budgetReferenceVisible,List.copyOf(work),initialAccountBindingAllowed,limitsProjection);
    }
    public AdminLlmKeyDetailResponse withInitialAccountBindingAllowed(@Nullable Boolean allowed) {
        return new AdminLlmKeyDetailResponse(id,name,purpose,status,expiresAt,lastUsedAt,rpm,tpm,concurrency,dailyTokens,quotaExhausted,
                creditLimit,creditLimitReset,creditAxisConnected,creditUsage,creditUsageAt,creditLimitRemaining,openrouterAccountId,
                openrouterAccountName,workspaceId,workspaceName,orgId,orgName,requestId,createdAt,revokedAt,creditAllowedModels,
                creditDeniedModels,passthroughEndpoints,chatEnabled,financeVisible,metadataVisible,observationVisible,accountReferences,
                tokenReferenceVisible,budgetReferenceVisible,allowedWorkIds,allowed,limitsProjection);
    }
    public AdminLlmKeyDetailResponse withLimitsProjection(@Nullable LlmKeyLimitsProjection projection) {
        return new AdminLlmKeyDetailResponse(id,name,purpose,status,expiresAt,lastUsedAt,rpm,tpm,concurrency,dailyTokens,quotaExhausted,
                creditLimit,creditLimitReset,creditAxisConnected,creditUsage,creditUsageAt,creditLimitRemaining,openrouterAccountId,
                openrouterAccountName,workspaceId,workspaceName,orgId,orgName,requestId,createdAt,revokedAt,creditAllowedModels,
                creditDeniedModels,passthroughEndpoints,chatEnabled,financeVisible,metadataVisible,observationVisible,accountReferences,
                tokenReferenceVisible,budgetReferenceVisible,allowedWorkIds,initialAccountBindingAllowed,projection);
    }
}
