package kr.ac.pusan.pickle.admin;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyDetailResponse;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyLimitsRequest;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeySummaryResponse;
import kr.ac.pusan.pickle.admin.dto.SuspendAdminLlmKeyRequest;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
            description = "기관과 워크스페이스, 발급 신청, 상태, 검색어로 키를 조회합니다. 키 평문과 해시, 평문 앞부분은 반환하지 않습니다.")
    public PageResponse<AdminLlmKeySummaryResponse> listAdminLlmKeys(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID workspaceId,
            @Parameter(description = "키를 발급한 신청 공개 ID")
            @RequestParam(required = false) UUID requestId,
            @Parameter(description = "불변 binding된 OpenRouter 사업 account 공개 ID")
            @RequestParam(required = false) UUID openrouterAccountId,
            @RequestParam(required = false) LlmApiKeyStatus status,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(principal, orgId, workspaceId, requestId, openrouterAccountId,
                status, query, page, size);
    }

    @GetMapping("/{keyId}")
    @Operation(summary = "관리자 LLM API 키 상세",
            description = "기관 범위 안의 키 운영 상태와 한도를 조회합니다. 인증 비밀이나 기록된 본문은 반환하지 않습니다.")
    public AdminLlmKeyDetailResponse getAdminLlmKey(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        return service.get(principal, keyId);
    }

    @PutMapping("/{keyId}/limits")
    @PreAuthorize("hasAnyRole('ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "관리자 LLM API 키 한도 교체",
            description = "여섯 한도 값을 한 번에 교체합니다. 시스템 운영자는 금액과 리셋 창을 바꿀 수 없습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "변경된 LLM API 키 상세",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = AdminLlmKeyDetailResponse.class))),
        @ApiResponse(responseCode = "503",
                description = "OpenRouter account binding 전환 미활성 (`OPENROUTER_ACCOUNT_BINDING_DISABLED`)",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(ref = "#/components/schemas/Problem")))
    })
    public AdminLlmKeyDetailResponse replaceAdminLlmKeyLimits(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Valid @RequestBody AdminLlmKeyLimitsRequest request,
            HttpServletRequest httpRequest) {
        return service.replaceLimits(principal, keyId, request, clientIp(httpRequest));
    }

    @PostMapping("/{keyId}/suspend")
    @PreAuthorize("hasAnyRole('ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "관리자 LLM API 키 정지",
            description = "활성 키를 정지합니다. 사유는 감사 기록에 남고 평문 요청 본문은 기록하지 않습니다.")
    public AdminLlmKeyDetailResponse suspendAdminLlmKey(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Valid @RequestBody SuspendAdminLlmKeyRequest request,
            HttpServletRequest httpRequest) {
        return service.suspend(principal, keyId, request.reason(), clientIp(httpRequest));
    }

    @PostMapping("/{keyId}/resume")
    @PreAuthorize("hasAnyRole('ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
    @Operation(summary = "관리자 LLM API 키 재개",
            description = "정지된 키를 다시 활성화합니다.")
    public AdminLlmKeyDetailResponse resumeAdminLlmKey(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            HttpServletRequest httpRequest) {
        return service.resume(principal, keyId, clientIp(httpRequest));
    }
}
