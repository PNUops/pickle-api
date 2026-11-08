package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountStatus;
import org.jspecify.annotations.Nullable;

public record OpenRouterAccountResponse(
        @Schema(description = "사업 계정 공개 ID")
        UUID id,
        @Schema(description = "소유 기관 공개 ID")
        UUID orgId,
        @Schema(description = "소유 기관 이름")
        String orgName,
        @Schema(description = "사업 계정 이름")
        String name,
        @Schema(description = "사업 계정 상태")
        OpenRouterAccountStatus status,
        @Schema(description = "이 사업 계정의 비용을 청구하는 사업. 없으면 null")
        @Nullable String program,
        @Schema(description = "이 사업 계정의 담당자. 없으면 null")
        @Nullable String contact,
        @Schema(description = "금액 한도가 양수인 키의 연결 대상으로 선택할 수 있는지")
        boolean eligibleForBinding,
        @Schema(description = "이 사업 계정에 현재 연결된 모든 상태의 LLM API 키 수예요. 살아 있는 키의 약속량과 열람 가능한 keyNavigation 부분집합, 기간별 소비 키 수는 각각 다른 값이에요.")
        @Nullable Long boundKeyCount,
        @Schema(description = "프로비저닝과 대사에 사용할 관리용 키가 있는지")
        @Nullable Boolean credentialAvailable,
        @Schema(description = "현재 ACTIVE 관리용 키의 상태 정보(키 값 제외). 없으면 null")
        @Nullable OpenRouterCredentialStateResponse activeCredential,
        @Schema(description = "STAGED 또는 RETIRING 관리용 키의 상태 정보(키 값 제외). 없으면 null")
        @Nullable OpenRouterCredentialStateResponse rotationCredential,
        @Schema(description = "저장된 사업 계정 credits와 예상값, 미관리 지출의 관측 상태")
        @Nullable OpenRouterAccountCreditsResponse credits,
        @Schema(description = "이 사업 계정에 연결된 살아 있는 키의 금액 한도 합계")
        @Nullable OpenRouterAccountAllocationResponse allocation,
        @Schema(description = "승인 화면에 처음 채울 유료 모델 허용 목록의 기본값이에요. 복사할 때만 쓰므로 "
                + "이 값을 바꿔도 이미 발급된 키에 자동 적용하지 않아요."
                + CreditModelPatterns.ALLOW_PATTERN_DESCRIPTION)
        @Nullable List<String> defaultCreditAllowedModels,
        @Schema(description = "승인 화면에 처음 채울 유료 모델 차단 목록의 기본값이에요. 복사할 때만 쓰므로 "
                + "이 값을 바꿔도 이미 발급된 키에 자동 적용하지 않아요."
                + CreditModelPatterns.DENY_PATTERN_DESCRIPTION)
        @Nullable List<String> defaultCreditDeniedModels,
        @Schema(description = "등록 시각")
        Instant createdAt,
        @Schema(description = "메타데이터의 최종 변경 시각")
        Instant updatedAt, boolean financeVisible, boolean credentialVisible, boolean policyReferenceVisible,
        @Nullable LlmAccountKeyNavigation keyNavigation) {
    public OpenRouterAccountResponse project(boolean finance, boolean credential, boolean metadata, boolean managementReference) {
        return new OpenRouterAccountResponse(id, orgId, orgName, name, status, metadata ? program : null, metadata ? contact : null, eligibleForBinding, metadata || finance ? boundKeyCount : null,
                credential ? credentialAvailable : null, credential ? activeCredential : null, credential ? rotationCredential : null,
                finance ? credits : null, finance ? allocation : null, finance || managementReference ? defaultCreditAllowedModels : null,
                finance || managementReference ? defaultCreditDeniedModels : null, createdAt, updatedAt, finance, credential, finance || managementReference,
                keyNavigation == null || finance ? keyNavigation : keyNavigation.withoutFinance());
    }

    public OpenRouterAccountResponse withKeyNavigation(@Nullable LlmAccountKeyNavigation navigation) {
        return new OpenRouterAccountResponse(id, orgId, orgName, name, status, program, contact, eligibleForBinding,
                boundKeyCount, credentialAvailable, activeCredential, rotationCredential, credits, allocation,
                defaultCreditAllowedModels, defaultCreditDeniedModels, createdAt, updatedAt, financeVisible,
                credentialVisible, policyReferenceVisible, navigation);
    }
}
