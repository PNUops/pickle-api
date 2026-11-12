package kr.ac.pusan.pickle.llm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code LlmKeySummary}. {@code workspaceName} is joined for
 * the list view.
 *
 * <p>A member of the owning workspace who holds no grant on a key still sees
 * that it exists, and the row is then <b>restricted</b>: {@code accessLimited}
 * is true, {@code ownerNames} says who to ask, and everything else is omitted
 * here rather than blanked in the console — a field the API sends has already
 * left the building. Unlike the VM, whose listed name is its SSH slug, a key's
 * name is only the label its requester chose, so a restricted row keeps it;
 * what it drops is {@code tokenPrefix}, the one field derived from the secret.
 *
 * <p>The token hash itself appears in <b>no</b> view, restricted or not: what
 * authenticates at the gateway is never part of any response.
 */
public record LlmKeySummaryResponse(
        UUID id,
        @Schema(description = "키 이름")
        String name,
        @Schema(description = "사용 목적이며, 접근 권한이 없으면 제공하지 않아요.")
        @Nullable String purpose,
        LlmApiKeyStatus status,
        @Schema(description = "목록에서 두 키를 구별하는 평문 앞부분이에요. "
                + "접근 권한이 없거나 아직 발급 전이면 제공하지 않아요.")
        @Nullable String tokenPrefix,
        @Schema(description = "만료 시각이며, null이면 만료가 없어요. 접근 권한이 없으면 제공하지 않아요.")
        @Nullable Instant expiresAt,
        @Schema(description = "마지막 사용 시각이에요. Gateway가 배치로 보고하므로 지연될 수 있어요. "
                + "접근 권한이 없으면 제공하지 않아요.")
        @Nullable Instant lastUsedAt,
        @Schema(description = "분당 요청 한도이며, null이면 Gateway 기본값을 따라요. 접근 권한이 없으면 제공하지 않아요.")
        @Nullable Integer rpm,
        @Schema(description = "분당 토큰 한도이며, null이면 Gateway 기본값을 따라요. 접근 권한이 없으면 제공하지 않아요.")
        @Nullable Integer tpm,
        @Schema(description = "동시 요청 한도이며, null이면 Gateway 기본값을 따라요. 접근 권한이 없으면 제공하지 않아요.")
        @Nullable Integer concurrency,
        @Schema(description = "프롬프트와 응답 본문 기록 설정이며, 접근 권한이 없으면 제공하지 않아요.")
        @Nullable Boolean recordBodies,
        @Schema(description = "소유 워크스페이스이며, 해당 행이 사라진 경우에만 null이에요.")
        @Nullable UUID workspaceId,
        String workspaceName,
        Instant createdAt,
        @Schema(description = "true면 이 키의 접근 권한이 없어 이름과 상태, 소유자 등 기본 정보만 제공해요.")
        boolean accessLimited,
        @Schema(description = "이 키의 소유자 이름이며, 접근을 요청할 상대예요.")
        List<String> ownerNames,
        @Schema(description = "접근 권한 목록을 관리할 수 있는지 나타내요. 키 접근 권한이 없어도 워크스페이스 소유자는 true예요.")
        boolean accessManageAllowed) {

    /** The full row a grant opens. */
    public static LlmKeySummaryResponse from(LlmApiKey key, UUID workspaceId,
            String workspaceName) {
        return new LlmKeySummaryResponse(key.getPublicId(), key.getName(), key.getPurpose(),
                key.getStatus(), key.getTokenPrefix(), key.getExpiresAt(), key.getLastUsedAt(),
                key.getRpm(), key.getTpm(), key.getConcurrency(), key.isRecordBodies(),
                workspaceId, workspaceName, key.getCreatedAt(), false, List.of(), false);
    }

    /**
     * Name, state and who to ask — nothing about the key itself. The token
     * prefix stays out: it exists so a list can tell two keys apart, and a
     * person this row is restricted for has no two keys to tell apart.
     */
    public static LlmKeySummaryResponse restricted(LlmApiKey key, UUID workspaceId,
            String workspaceName, List<String> ownerNames, boolean accessManageAllowed) {
        return new LlmKeySummaryResponse(key.getPublicId(), key.getName(), null, key.getStatus(),
                null, null, null, null, null, null, null, workspaceId, workspaceName,
                key.getCreatedAt(), true, ownerNames, accessManageAllowed);
    }
}
