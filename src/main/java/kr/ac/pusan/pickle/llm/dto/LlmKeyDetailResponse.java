package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.llm.PassthroughEndpoints;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeyDetail}. Reaching it at all takes a grant —
 * a non-member is answered 404 and a member without a grant an honest 403 —
 * so unlike the summary it has no restricted shape.
 *
 * <p>The token hash appears in no field, and neither does anything derived
 * from it beyond the prefix the plaintext was minted with. A lost key is
 * reissued, never recovered, and this response is one of the places that
 * makes that claim true.
 */
public record LlmKeyDetailResponse(
        UUID id,
        @Schema(description = "키 이름")
        String name,
        @Schema(description = "용도")
        @Nullable String purpose,
        LlmApiKeyStatus status,
        @Schema(description = "두 키를 구별하는 평문 앞부분이에요. 아직 발급 전이면 null이에요.")
        @Nullable String tokenPrefix,
        @Schema(description = "만료 시각이며, null이면 만료가 없어요.")
        @Nullable Instant expiresAt,
        @Schema(description = "마지막 사용 시각이에요. Gateway가 배치로 보고하므로 지연될 수 있어요.")
        @Nullable Instant lastUsedAt,
        @Schema(description = "분당 요청 한도이며, null이면 Gateway 기본값을 따라요.")
        @Nullable Integer rpm,
        @Schema(description = "분당 토큰 한도이며, null이면 Gateway 기본값을 따라요.")
        @Nullable Integer tpm,
        @Schema(description = "동시 요청 한도이며, null이면 Gateway 기본값을 따라요.")
        @Nullable Integer concurrency,
        @Schema(description = "일일 토큰 한도이며, null이면 일일 한도가 없고 0이면 자체 서빙 모델을 쓸 수 없어요.")
        @Nullable Long dailyTokens,
        @Schema(description = "프롬프트와 응답 본문 기록 설정")
        boolean recordBodies,
        @Schema(description = "유료 모델의 금액 한도예요(USD 크레딧). 0이면 유료 모델을 쓸 수 없어요.")
        BigDecimal creditLimit,
        @Schema(description = "금액 한도 리셋 창이에요. null이면 리셋 없는 총액 상한이며, 리셋 창은 UTC 자정을 기준으로 해요.")
        @Nullable CreditLimitReset creditLimitReset,
        @Schema(description = "유료 모델 연결 상태예요. 금액 한도를 부여해도 아직 키 연결 전이면 false예요.")
        boolean creditAxisConnected,
        @Schema(description = "이 키의 유료 모델 허용 목록이에요. 빈 배열이면 이 목록으로 제한하지 않아요. "
                + "자체 서빙 모델에는 적용하지 않아요."
                + CreditModelPatterns.ALLOW_PATTERN_DESCRIPTION)
        List<String> creditAllowedModels,
        @Schema(description = "이 키의 유료 모델 차단 목록이에요. "
                + "빈 배열이면 이 목록으로 차단하지 않아요. 허용 목록과 겹치면 차단을 적용해요."
                + CreditModelPatterns.DENY_PATTERN_DESCRIPTION)
        List<String> creditDeniedModels,
        @ArraySchema(schema = @Schema(allowableValues = {PassthroughEndpoints.CHAT,
                PassthroughEndpoints.WEB_SEARCH, PassthroughEndpoints.PDF_OCR,
                PassthroughEndpoints.CONTAINERS, PassthroughEndpoints.IMAGES,
                PassthroughEndpoints.EMBEDDINGS, PassthroughEndpoints.RERANK,
                PassthroughEndpoints.SPEECH, PassthroughEndpoints.TRANSCRIPTIONS,
                PassthroughEndpoints.VIDEOS, PassthroughEndpoints.BATCHES,
                PassthroughEndpoints.FILES}),
                arraySchema = @Schema(description = "이 키에 부여된 기능 권한이에요. "
                        + "빈 배열이면 chat을 포함해 어떤 기능도 쓸 수 없어요. "
                        + "모델 조회에는 이 목록을 적용하지 않아요."))
        List<String> passthroughEndpoints,
        @Schema(description = "소유 워크스페이스이며, 해당 행이 사라진 경우에만 null이에요.")
        @Nullable UUID workspaceId,
        String workspaceName,
        Instant createdAt,
        @Schema(description = "폐기한 시각이며, 폐기하지 않았으면 null이에요.")
        @Nullable Instant revokedAt,
        @Schema(description = "요청자가 이 키의 접근 목록에서 받은 등급")
        @Nullable ResourceRole myResourceRole,
        @Schema(description = "접근 권한 목록을 관리할 수 있는지")
        boolean accessManageAllowed) {

    public static LlmKeyDetailResponse from(LlmApiKey key, UUID workspaceId, String workspaceName,
            List<String> creditAllowedModels, List<String> creditDeniedModels,
            List<String> passthroughEndpoints,
            @Nullable ResourceRole myResourceRole, boolean accessManageAllowed) {
        return new LlmKeyDetailResponse(key.getPublicId(), key.getName(), key.getPurpose(),
                key.getStatus(), key.getTokenPrefix(), key.getExpiresAt(), key.getLastUsedAt(),
                key.getRpm(), key.getTpm(), key.getConcurrency(), key.getDailyTokens(),
                key.isRecordBodies(),
                key.getCreditLimit(), key.getCreditLimitReset(), key.isCreditAxisConnected(),
                creditAllowedModels, creditDeniedModels, passthroughEndpoints,
                workspaceId, workspaceName,
                key.getCreatedAt(), key.getRevokedAt(), myResourceRole, accessManageAllowed);
    }
}
