package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountStatus;
import org.jspecify.annotations.Nullable;

public record OpenRouterAccountResponse(
        @Schema(description = "Account 공개 ID")
        UUID id,
        @Schema(description = "소유 기관 공개 ID")
        UUID orgId,
        @Schema(description = "소유 기관 이름")
        String orgName,
        @Schema(description = "사업 account 이름")
        String name,
        @Schema(description = "Account lifecycle 상태")
        OpenRouterAccountStatus status,
        @Schema(description = "재원 참조. 없으면 null")
        @Nullable String fundingReference,
        @Schema(description = "증빙 참조. 없으면 null")
        @Nullable String evidenceReference,
        @Schema(description = "현재 positive-credit key binding에 선택할 수 있는지")
        boolean eligibleForBinding,
        @Schema(description = "이 account에 불변 binding된 Pickle LLM key 수")
        long boundKeyCount,
        @Schema(description = "프로비저닝과 대사에 사용할 management credential이 있는지")
        boolean credentialAvailable,
        @Schema(description = "현재 ACTIVE credential의 secret-free 상태. 없으면 null")
        @Nullable OpenRouterCredentialStateResponse activeCredential,
        @Schema(description = "STAGED 또는 RETIRING credential의 secret-free 상태. 없으면 null")
        @Nullable OpenRouterCredentialStateResponse rotationCredential,
        @Schema(description = "등록 시각")
        Instant createdAt,
        @Schema(description = "Metadata 최종 변경 시각")
        Instant updatedAt) {
}
