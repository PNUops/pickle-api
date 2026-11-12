package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminLlmAccountUsageResponse;
import kr.ac.pusan.pickle.admin.dto.LlmConsumerSort;
import kr.ac.pusan.pickle.admin.dto.ConfirmOpenRouterAccountRequest;
import kr.ac.pusan.pickle.admin.dto.CreateOpenRouterAccountRequest;
import kr.ac.pusan.pickle.admin.dto.FinalizeOpenRouterCredentialRequest;
import kr.ac.pusan.pickle.admin.dto.OpenRouterAccountResponse;
import kr.ac.pusan.pickle.admin.dto.RegisterOpenRouterCredentialRequest;
import kr.ac.pusan.pickle.admin.dto.StageOpenRouterCredentialRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateOpenRouterAccountRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "admin", description = "관리자 API")
@RestController
@RequestMapping("/api/v1/admin/llm/accounts")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminOpenRouterAccountController {

    private static final String WRITERS =
            "hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_ADMIN')";

    private final AdminOpenRouterAccountService service;

    public AdminOpenRouterAccountController(AdminOpenRouterAccountService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "OpenRouter 사업 계정 목록",
            description = "관리 범위의 OpenRouter 사업 계정과 관리용 키 상태를 조회해요. 인증 정보와 vendor 내부 식별자는 반환하지 않아요.")
    public List<OpenRouterAccountResponse> listAdminLlmAccounts(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId) {
        return service.list(principal, orgId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITERS)
    @Operation(summary = "OpenRouter 사업 계정 등록",
            description = "이름을 확인해 기관의 사업 계정 정보를 등록해요. 관리용 키는 별도 등록 경로에서 검증하며 교체는 대기 등록으로 진행해요.")
    public OpenRouterAccountResponse createAdminLlmAccount(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.create(principal, request, clientIp(httpRequest));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "OpenRouter 사업 계정 상세",
            description = "관리 범위의 사업 계정 정보와 유료 모델 연결 가능 여부, 연결된 키 수, 관리용 키 상태를 조회해요. 키 값은 반환하지 않아요.")
    public OpenRouterAccountResponse getAdminLlmAccount(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId) {
        return service.get(principal, accountId);
    }

    @GetMapping("/{accountId}/usage")
    @Operation(summary = "OpenRouter 사업 계정 사용량",
            description = "현재 이 계정에 연결된 키를 기준으로 플랫폼에 기록된 사용량과 금액을 집계해요. "
                    + "**공급자의 지출액이나 잔액과는 다른 값이에요.** 공급자 값은 계정 상세의 "
                    + "credits에서 확인하며 집계 기간도 달라요. 키를 확인하지 못한 요청은 어느 계정에도 "
                    + "귀속하지 않아요. 공급자가 금액을 알려 주지 않은 요청은 0으로 보지 않고 "
                    + "금액 미확인으로 합계에서 제외해요. 금액마다 가격이 확인된 요청 수도 함께 반환해요.")
    public AdminLlmAccountUsageResponse getAdminLlmAccountUsage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Parameter(description = "오늘을 포함해 거슬러 올라갈 일수")
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "REPORTED_COST_DESC") LlmConsumerSort sort) {
        return service.usage(principal, accountId, days, page, size, sort);
    }

    @PatchMapping("/{accountId}")
    @PreAuthorize(WRITERS)
    @Operation(summary = "OpenRouter 사업 계정 정보 수정",
            description = "이름과 사업, 담당자, 상태, 기본 유료 모델 허용 목록과 차단 목록 중 보낸 항목만 바꿔요. 만료 시각이 없거나 현재보다 뒤인 연결 키 중 PENDING, ACTIVE, SUSPENDED 상태가 있으면 계정을 보관할 수 없어요. 기본 목록 변경은 새 승인이나 발급 때 참고하며 기존 키에는 자동 적용하지 않아요.")
    public OpenRouterAccountResponse updateAdminLlmAccount(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody UpdateOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.update(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITERS)
    @Operation(summary = "OpenRouter 관리 credential 최초 등록",
            description = "credential이 없는 ACTIVE 사업 계정에 관리용 키를 등록해요. 계정 이름을 확인한 뒤 credits와 키 목록 조회, 실제 검증용 키 생성과 비활성화 및 삭제로 management 전용 권한과 vendor workspace를 검증해요. billing identity marker가 없으면 만들고 같은 요청에서 ACTIVE 상태로 저장해요. 이미 credential이 있으면 거절하며 교체는 대기 등록으로 진행해요.")
    public OpenRouterAccountResponse registerAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody RegisterOpenRouterCredentialRequest request,
            HttpServletRequest httpRequest) {
        return service.registerCredential(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/staged")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITERS)
    @Operation(summary = "OpenRouter 관리 credential 교체 대기 등록",
            description = "쓰고 있는 ACTIVE credential을 교체할 후보를 등록해요. 계정 이름을 확인한 뒤 credits와 키 목록 조회, 실제 검증용 키 생성과 비활성화 및 삭제로 management 전용 권한과 vendor workspace를 검증하고 STAGED 상태로 저장해요. ACTIVE credential이 없으면 거절하며 최초 등록은 별도 경로를 써요. 평문과 credential 조각은 응답하지 않아요.")
    public OpenRouterAccountResponse stageAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody StageOpenRouterCredentialRequest request,
            HttpServletRequest httpRequest) {
        return service.stage(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/staged/activate")
    @PreAuthorize(WRITERS)
    @Operation(summary = "대기 중 OpenRouter credential 활성화",
            description = "이름을 확인한 뒤 STAGED credential을 다시 검증해요. 교체할 때는 기존 ACTIVE가 만든 검증용 키를 새 credential로 조회하고 수정한 뒤 삭제해요. 이후 두 상태를 원자적으로 ACTIVE와 RETIRING으로 바꿔요.")
    public OpenRouterAccountResponse activateAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody ConfirmOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.activate(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/staged/cancel")
    @PreAuthorize(WRITERS)
    @Operation(summary = "대기 중 OpenRouter credential 취소",
            description = "이름을 확인한 뒤 아직 활성화하지 않은 STAGED credential 암호문을 삭제해요. ACTIVE credential에는 영향을 주지 않아요.")
    public OpenRouterAccountResponse cancelAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody ConfirmOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.cancel(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/retiring/rollback")
    @PreAuthorize(WRITERS)
    @Operation(summary = "OpenRouter credential 교체 되돌리기",
            description = "이름을 확인하고 이전 RETIRING credential을 재검증한 뒤 현재 ACTIVE를 STAGED로, 이전 RETIRING을 ACTIVE로 원자적으로 되돌려요. vendor credential 자체는 API가 폐기하지 않아요.")
    public OpenRouterAccountResponse rollbackAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody ConfirmOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.rollback(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/retiring/finalize")
    @PreAuthorize(WRITERS)
    @Operation(summary = "이전 OpenRouter credential 정리",
            description = "새 ACTIVE credential의 활성화 이후 key reconciliation이 성공하고 운영자가 이전 관리용 키의 폐기를 확인해야 해요. 이전 키의 원격 credits 조회가 인증 또는 권한 오류로 거절되는지 확인한 뒤 RETIRING 암호문을 삭제해요.")
    public OpenRouterAccountResponse finalizeAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody FinalizeOpenRouterCredentialRequest request,
            HttpServletRequest httpRequest) {
        return service.finalizeRetiring(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/active/delete")
    @PreAuthorize(WRITERS)
    @Operation(summary = "사용하지 않는 OpenRouter credential 삭제",
            description = "연결된 키와 진행 중인 교체가 없어야 해요. 이름과 운영자의 OpenRouter 관리용 키 폐기 확인을 받고, 해당 키의 원격 credits 조회가 인증 또는 권한 오류로 거절되는지 확인한 뒤 ACTIVE 암호문을 삭제해요. API가 관리용 키 자체를 폐기하지는 않아요.")
    public OpenRouterAccountResponse deleteActiveAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody FinalizeOpenRouterCredentialRequest request,
            HttpServletRequest httpRequest) {
        return service.deleteActive(principal, accountId, request, clientIp(httpRequest));
    }
}
