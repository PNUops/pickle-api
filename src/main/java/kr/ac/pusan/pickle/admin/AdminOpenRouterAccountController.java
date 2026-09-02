package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.ConfirmOpenRouterAccountRequest;
import kr.ac.pusan.pickle.admin.dto.CreateOpenRouterAccountRequest;
import kr.ac.pusan.pickle.admin.dto.FinalizeOpenRouterCredentialRequest;
import kr.ac.pusan.pickle.admin.dto.OpenRouterAccountResponse;
import kr.ac.pusan.pickle.admin.dto.StageOpenRouterCredentialRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateOpenRouterAccountRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
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
            "hasAnyRole('ORG_MANAGER', 'ORG_ADMIN', 'SYS_ADMIN')";

    private final AdminOpenRouterAccountService service;

    public AdminOpenRouterAccountController(AdminOpenRouterAccountService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "OpenRouter 사업 account 목록",
            description = "관리 범위의 사업별 OpenRouter account와 credential 상태를 조회합니다. 인증 정보와 vendor 내부 식별자는 반환하지 않습니다.")
    public List<OpenRouterAccountResponse> listAdminLlmAccounts(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId) {
        return service.list(principal, orgId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITERS)
    @RequireReauth
    @Operation(summary = "OpenRouter 사업 account 등록",
            description = "기관에 사업별 account metadata를 등록합니다. 재인증과 이름 확인이 필요하며 management credential은 별도 stage 작업으로 검증합니다.")
    public OpenRouterAccountResponse createAdminLlmAccount(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.create(principal, request, clientIp(httpRequest));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "OpenRouter 사업 account 상세",
            description = "관리 범위의 account metadata, binding 가능 여부, 연결된 key 수와 secret-free credential lifecycle 상태를 조회합니다.")
    public OpenRouterAccountResponse getAdminLlmAccount(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId) {
        return service.get(principal, accountId);
    }

    @PatchMapping("/{accountId}")
    @PreAuthorize(WRITERS)
    @Operation(summary = "OpenRouter 사업 account 정보 수정",
            description = "이름·사업·담당자·상태 중 보낸 항목만 변경합니다. 활성 또는 미만료 key가 연결된 account는 보관할 수 없습니다.")
    public OpenRouterAccountResponse updateAdminLlmAccount(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody UpdateOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.update(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/staged")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITERS)
    @RequireReauth
    @Operation(summary = "OpenRouter 관리 credential 검증 및 대기 등록",
            description = "재인증과 account 이름 확인 뒤 management 전용 권한과 vendor workspace를 disposable key로 검증하고 STAGED 상태로 저장합니다. 평문과 credential 조각은 응답하지 않습니다.")
    public OpenRouterAccountResponse stageAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody StageOpenRouterCredentialRequest request,
            HttpServletRequest httpRequest) {
        return service.stage(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/staged/activate")
    @PreAuthorize(WRITERS)
    @RequireReauth
    @Operation(summary = "대기 중 OpenRouter credential 활성화",
            description = "재인증과 이름 확인 뒤 STAGED credential을 다시 검증합니다. 교체 시 기존 ACTIVE가 만든 disposable key를 새 credential로 조회·수정·삭제한 뒤 두 상태를 원자적으로 ACTIVE와 RETIRING으로 전환합니다.")
    public OpenRouterAccountResponse activateAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody ConfirmOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.activate(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/staged/cancel")
    @PreAuthorize(WRITERS)
    @RequireReauth
    @Operation(summary = "대기 중 OpenRouter credential 취소",
            description = "재인증과 이름 확인 뒤 아직 활성화하지 않은 STAGED credential 암호문을 삭제합니다. ACTIVE credential에는 영향을 주지 않습니다.")
    public OpenRouterAccountResponse cancelAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody ConfirmOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.cancel(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/retiring/rollback")
    @PreAuthorize(WRITERS)
    @RequireReauth
    @Operation(summary = "OpenRouter credential 교체 되돌리기",
            description = "재인증과 이름 확인 뒤 현재 ACTIVE를 STAGED로, 이전 RETIRING을 ACTIVE로 원자적으로 되돌립니다. Vendor credential은 API가 폐기하지 않습니다.")
    public OpenRouterAccountResponse rollbackAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody ConfirmOpenRouterAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.rollback(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/retiring/finalize")
    @PreAuthorize(WRITERS)
    @RequireReauth
    @Operation(summary = "이전 OpenRouter credential 정리",
            description = "새 ACTIVE credential로 key reconciliation이 성공하고 운영자가 vendor console에서 이전 management key를 폐기했음을 확인한 뒤 RETIRING 암호문을 삭제합니다.")
    public OpenRouterAccountResponse finalizeAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody FinalizeOpenRouterCredentialRequest request,
            HttpServletRequest httpRequest) {
        return service.finalizeRetiring(principal, accountId, request, clientIp(httpRequest));
    }

    @PostMapping("/{accountId}/credentials/active/delete")
    @PreAuthorize(WRITERS)
    @RequireReauth
    @Operation(summary = "사용하지 않는 OpenRouter credential 삭제",
            description = "연결된 key와 rotation이 없고 운영자가 vendor console 폐기를 확인한 경우에만 ACTIVE 암호문을 삭제합니다. API는 vendor management key 자체를 폐기하지 않습니다.")
    public OpenRouterAccountResponse deleteActiveAdminLlmAccountCredential(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody FinalizeOpenRouterCredentialRequest request,
            HttpServletRequest httpRequest) {
        return service.deleteActive(principal, accountId, request, clientIp(httpRequest));
    }
}
