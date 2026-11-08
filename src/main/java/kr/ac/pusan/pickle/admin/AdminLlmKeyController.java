package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.util.UUID;
import java.math.BigDecimal;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyDetailResponse;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyExpiryRequest;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyLimitsRequest;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeySummaryResponse;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyUsageResponse;
import kr.ac.pusan.pickle.admin.dto.SuspendAdminLlmKeyRequest;
import kr.ac.pusan.pickle.admin.dto.LlmKeyCohort;
import kr.ac.pusan.pickle.admin.dto.LlmKeySort;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.admin.dto.InstitutionAdminLlmModelsResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code admin}, LLM API key operations. */
@Tag(name = "admin", description = "관리자 API")
@RestController
@RequestMapping("/api/v1/admin/llm/keys")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminLlmKeyController {

    private final AdminLlmKeyService service;

    public AdminLlmKeyController(AdminLlmKeyService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "관리자 LLM API 키 목록",
            description = "각 업무가 허가한 키의 metadata와 관측, 금융, 명령 참조를 조회해요. 기관과 워크스페이스, 발급 신청, 상태, 검색어는 필터예요. 키 평문과 해시, 평문 앞부분은 반환하지 않아요.")
    public PageResponse<AdminLlmKeySummaryResponse> listAdminLlmKeys(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID workspaceId,
            @Parameter(description = "키를 발급한 신청 공개 ID")
            @RequestParam(required = false) UUID requestId,
            @Parameter(description = "연결된 사업 계정의 공개 ID")
            @RequestParam(required = false) UUID openrouterAccountId,
            @RequestParam(required = false) LlmApiKeyStatus status,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "현재 키 행의 모집단이에요. 승인 후 키 생성 전 계획은 포함하지 않아요.")
            @RequestParam(defaultValue = "ALL") LlmKeyCohort cohort,
            @Parameter(description = "안정된 서버 정렬이에요. 관측값이나 금액을 기준으로 정렬하려면 조회 대상 전체에 대해 그 정렬 기준의 관측 또는 금융 정보를 읽을 수 있어야 해요.")
            @RequestParam(defaultValue = "NEWEST") LlmKeySort sort,
            @Parameter(description = "저장 정책과 기능 권한이 허용하는 공개 모델명이에요. 예산과 발급, 라우팅, 실제 처리 성공을 보장하지 않아요.")
            @RequestParam(required = false) String policyModel,
            @RequestParam(defaultValue = "chat") String policyFeature) {
        return service.list(principal, orgId, workspaceId, requestId, openrouterAccountId,
                status, query, page, size, cohort, sort, policyModel, policyFeature);
    }

    @GetMapping("/{keyId}")
    @Operation(summary = "관리자 LLM API 키 상세",
            description = "실제 키에 대한 업무별로 운영 상태와 현재 허용값을 보여 주고 금융 필드는 별도로 판정해요. 인증 비밀이나 기록된 본문은 반환하지 않아요.")
    public AdminLlmKeyDetailResponse getAdminLlmKey(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Parameter(description = "true이면 현재 허용된 금액 제안의 읽기 projection을 포함해요. false이면 제안 파라미터를 함께 보낼 수 없어요. 저장과 계정 결합, 외부 호출은 하지 않아요.")
            @RequestParam(defaultValue = "false") boolean projectLimits,
            @Parameter(description = "제안 금액(USD)이에요. 생략하면 현재 값을 유지하고 빈 값과 null은 허용하지 않아요. 0 이상, 정수 10자리와 소수 2자리까지이며 0은 유료 모델을 닫는 제안이에요.")
            @RequestParam(required = false) @DecimalMin(value = "0", message = "금액 한도는 0 이상으로 입력해 주세요.")
            @Digits(integer = 10, fraction = 2, message = "금액 한도는 소수점 둘째 자리까지 입력해 주세요.") BigDecimal proposedCreditLimit,
            @Parameter(description = "false는 리셋 제안 없음이고, true이면서 proposedCreditLimitReset을 생략하면 명시한 총액 한도예요.")
            @RequestParam(defaultValue = "false") boolean proposedCreditLimitResetSet,
            @Parameter(description = "resetSet=true일 때 제안하는 DAILY/WEEKLY/MONTHLY 창이에요. resetSet=true이면서 이 값을 생략하면 명시한 총액 한도(null), resetSet=false는 리셋 변경 없음이에요.")
            @RequestParam(required = false) CreditLimitReset proposedCreditLimitReset,
            @Parameter(description = "기존 최초 계정 연결 규칙으로 평가할 공개 ID예요. 계정 전환이나 재결합은 아니에요.")
            @RequestParam(required = false) UUID proposedOpenrouterAccountId,
            HttpServletRequest httpRequest) {
        boolean amountSet = httpRequest.getParameterMap().containsKey("proposedCreditLimit");
        boolean resetParameter = httpRequest.getParameterMap().containsKey("proposedCreditLimitReset");
        boolean accountParameter = httpRequest.getParameterMap().containsKey("proposedOpenrouterAccountId");
        boolean anyDraft = amountSet || resetParameter || accountParameter
                || httpRequest.getParameterMap().containsKey("proposedCreditLimitResetSet");
        return service.get(principal, keyId, projectLimits, anyDraft, amountSet, proposedCreditLimit,
                proposedCreditLimitResetSet, resetParameter, proposedCreditLimitReset, accountParameter,
                proposedOpenrouterAccountId);
    }

    @GetMapping("/{keyId}/usage")
    @Operation(summary = "관리자 LLM API 키 사용량",
            description = "실제 키의 관측 읽기와 금융 읽기 권한을 각각 판정하는 사용량 응답이에요. 허용하지 않은 조회 항목은 null로 반환하며 0이나 빈 목록과 구분해요. "
                    + "권한으로 볼 수 있는 일별 사용량은 소유자 조회와 같은 값과 계산을 써요. "
                    + "일별 금액과 경로별 사용량, 공급자가 요청과 다른 모델로 응답한 사례를 나눠 보여 줘요. "
                    + "하루는 한국 시간 기준이며 호출이 없던 날도 0으로 채워요. "
                    + "오늘 값은 게이트웨이의 배치 보고에 따라 달라질 수 있어요.")
    public AdminLlmKeyUsageResponse getAdminLlmKeyUsage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Parameter(description = "오늘을 포함해 거슬러 올라갈 일수")
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return service.usage(principal, keyId, days);
    }

    @GetMapping("/{keyId}/models")
    @Operation(summary = "관리자 LLM API 키로 호출할 수 있는 모델",
            description = "실제 키를 위한 모델 후보예요. 유료 정책 참조는 금융 읽기나 해당 키의 예산 명령 권한이 있을 때만 반환해요. "
                    + "허용 목록을 정할 때 실제로 어떤 모델이 남는지 확인해요. "
                    + "최종 판정은 호출 시점에 이뤄지므로 여기 있는 모델이 항상 응답한다고 보장하지 않아요.")
    public InstitutionAdminLlmModelsResponse listAdminLlmKeyModels(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        return service.models(principal, keyId);
    }

    @PutMapping("/{keyId}/limits")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "관리자 LLM API 키 한도 교체",
            description = "보낸 한도만 현재 업무 권한으로 바꾸고 생략한 값은 유지해요. 시스템 운영자는 금액과 리셋 창, 유료 모델 및 유료 기능 정책을 바꿀 수 없어요.")
    @ApiResponse(responseCode = "200", description = "변경된 LLM API 키 상세",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AdminLlmKeyDetailResponse.class)))
    public AdminLlmKeyDetailResponse replaceAdminLlmKeyLimits(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Valid @RequestBody AdminLlmKeyLimitsRequest request,
            HttpServletRequest httpRequest) {
        return service.replaceLimits(principal, keyId, request, clientIp(httpRequest));
    }

    @PatchMapping("/{keyId}/expiry")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "관리자 LLM API 키 만료일 변경",
            description = "키의 종료일을 바꿔요. 종료일은 KST 기준 그날을 포함하며 승인할 때와 같이 계산해요. "
                    + "미래 종료일로 만료 조건을 해소할 수 있어요. 정지 상태와 최초 발급 조건은 그대로 적용돼요. 폐기된 키는 바꿀 수 없고, "
                    + "OpenRouter 키가 발급돼 있고 현재 만료일이 정해져 있으면 그보다 뒤로 미룰 수 없어요.")
    @ApiResponse(responseCode = "200", description = "변경된 LLM API 키 상세",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AdminLlmKeyDetailResponse.class)))
    public AdminLlmKeyDetailResponse updateAdminLlmKeyExpiry(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Valid @RequestBody AdminLlmKeyExpiryRequest request,
            HttpServletRequest httpRequest) {
        return service.updateExpiry(principal, keyId, request.endDate(), clientIp(httpRequest));
    }

    @PostMapping("/{keyId}/suspend")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "관리자 LLM API 키 정지",
            description = "활성 키를 정지해요. 사유는 감사 기록에 남고 평문 요청 본문은 감사 기록에 남기지 않아요.")
    public AdminLlmKeyDetailResponse suspendAdminLlmKey(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Valid @RequestBody SuspendAdminLlmKeyRequest request,
            HttpServletRequest httpRequest) {
        return service.suspend(principal, keyId, request.reason(), clientIp(httpRequest));
    }

    @PostMapping("/{keyId}/resume")
    @PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "관리자 LLM API 키 재개",
            description = "정지된 키를 다시 활성화해요.")
    public AdminLlmKeyDetailResponse resumeAdminLlmKey(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            HttpServletRequest httpRequest) {
        return service.resume(principal, keyId, clientIp(httpRequest));
    }
}
