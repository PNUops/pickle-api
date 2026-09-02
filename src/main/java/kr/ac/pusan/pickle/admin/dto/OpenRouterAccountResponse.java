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
        @Schema(description = "사업 계정 이름")
        String name,
        @Schema(description = "Account lifecycle 상태")
        OpenRouterAccountStatus status,
        @Schema(description = "이 account가 청구되는 사업. 없으면 null")
        @Nullable String program,
        @Schema(description = "이 account를 물어볼 담당자. 없으면 null")
        @Nullable String contact,
        @Schema(description = "현재 positive-credit key binding에 선택할 수 있는지")
        boolean eligibleForBinding,
        @Schema(description = "이 사업 계정에 연결된 LLM API 키 수")
        long boundKeyCount,
        @Schema(description = "프로비저닝과 대사에 사용할 management credential이 있는지")
        boolean credentialAvailable,
        @Schema(description = "현재 ACTIVE credential의 secret-free 상태. 없으면 null")
        @Nullable OpenRouterCredentialStateResponse activeCredential,
        @Schema(description = "STAGED 또는 RETIRING credential의 secret-free 상태. 없으면 null")
        @Nullable OpenRouterCredentialStateResponse rotationCredential,
        @Schema(description = "DB cache에서 읽은 account credits·예상·미관리 지출 관측 상태")
        OpenRouterAccountCreditsResponse credits,
        @Schema(description = "등록 시각")
        Instant createdAt,
        @Schema(description = "Metadata 최종 변경 시각")
        Instant updatedAt) {
}
