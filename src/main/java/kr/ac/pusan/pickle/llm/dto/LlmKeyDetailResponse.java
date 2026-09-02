package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
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
        @Schema(description = "평문 앞부분 — 두 키를 구별하기 위한 값입니다. 아직 발급 전이면 null입니다.")
        @Nullable String tokenPrefix,
        @Schema(description = "만료 시각. null이면 만료가 없습니다.")
        @Nullable Instant expiresAt,
        @Schema(description = "마지막 사용 시각. 게이트웨이가 배치로 보고하므로 지연될 수 있습니다.")
        @Nullable Instant lastUsedAt,
        @Schema(description = "분당 요청 한도. null이면 게이트웨이 기본값을 따릅니다.")
        @Nullable Integer rpm,
        @Schema(description = "분당 토큰 한도. null이면 게이트웨이 기본값을 따릅니다.")
        @Nullable Integer tpm,
        @Schema(description = "동시 요청 한도. null이면 게이트웨이 기본값을 따릅니다.")
        @Nullable Integer concurrency,
        @Schema(description = "프롬프트·응답 본문 기록 여부")
        boolean recordBodies,
        @Schema(description = "상용(금액) 축 한도, USD 크레딧. 0이면 상용 모델을 쓸 수 없습니다.")
        BigDecimal creditLimit,
        @Schema(description = "금액 한도 리셋 창. null이면 리셋 없는 총액 상한입니다. 창은 UTC 자정 기준입니다.")
        @Nullable CreditLimitReset creditLimitReset,
        @Schema(description = "상용 축 사용 가능 여부. 금액 한도가 부여됐지만 아직 연결 전이면 false입니다.")
        boolean creditAxisConnected,
        @Schema(description = "상용(금액) 축에서 이 키가 쓸 수 있는 모델 목록. 빈 배열이면 제한이 "
                + "없습니다. 자체 서빙 모델은 이 목록과 무관하게 쓸 수 있습니다.")
        List<String> creditAllowedModels,
        @Schema(description = "소유 워크스페이스. 행이 사라진 경우에만 null입니다.")
        @Nullable UUID workspaceId,
        String workspaceName,
        Instant createdAt,
        @Schema(description = "회수된 시각. 회수되지 않았으면 null입니다.")
        @Nullable Instant revokedAt,
        @Schema(description = "요청자가 이 키의 접근 목록에서 받은 등급")
        @Nullable ResourceRole myResourceRole,
        @Schema(description = "접근 권한 목록을 관리할 수 있는지")
        boolean accessManageAllowed) {

    public static LlmKeyDetailResponse from(LlmApiKey key, UUID workspaceId, String workspaceName,
            List<String> creditAllowedModels, @Nullable ResourceRole myResourceRole,
            boolean accessManageAllowed) {
        return new LlmKeyDetailResponse(key.getPublicId(), key.getName(), key.getPurpose(),
                key.getStatus(), key.getTokenPrefix(), key.getExpiresAt(), key.getLastUsedAt(),
                key.getRpm(), key.getTpm(), key.getConcurrency(), key.isRecordBodies(),
                key.getCreditLimit(), key.getCreditLimitReset(), key.isCreditAxisConnected(),
                creditAllowedModels, workspaceId, workspaceName, key.getCreatedAt(),
                key.getRevokedAt(), myResourceRole, accessManageAllowed);
    }
}
