package kr.ac.pusan.pickle.access;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import kr.ac.pusan.pickle.access.dto.AddResourceAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.ResourceAccessGrantView;
import kr.ac.pusan.pickle.access.dto.ResourceAccessListResponse;
import kr.ac.pusan.pickle.access.dto.UpdateResourceAccessGrantRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code llm-key-access}: who may reach one LLM API key, and at
 * what rung.
 *
 * <p>The rules are {@link ResourceAccessGrantService}'s and know nothing about
 * keys; this class is the key's path into them, the same pass-through the VM
 * has in {@link VmAccessGrantController}. What the type contributes — its
 * refusal sentences and audit names — lives in {@link kr.ac.pusan.pickle.llm.LlmKeyResourceAdapter}
 * until the key's resource adapter carries them.
 */
@Tag(name = "llm-key-access",
        description = "LLM API 키 접근 권한 — 이 키에 누가 접근할 수 있는지를 정합니다.")
@RestController
@RequestMapping("/api/v1/llm-keys/{keyId}/access")
public class LlmKeyAccessGrantController {

    private final ResourceAccessGrantService service;

    public LlmKeyAccessGrantController(ResourceAccessGrantService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "접근 권한 목록",
            description = "이 키의 접근 권한 전체와, 그 목록이 어느 키의 것인지 알려 주는 최소 정보입니다. "
                    + "키 소유자와 워크스페이스 소유자만 볼 수 있습니다.")
    public ResourceAccessListResponse listLlmKeyAccessGrants(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        return service.list(principal, ResourceType.LLM_API_KEY, keyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireReauth
    @Operation(summary = "접근 권한 부여",
            description = "지정한 사용자 또는 소유 워크스페이스 전체에 이 키의 접근 권한을 부여합니다. "
                    + "사용자는 이 키를 소유한 워크스페이스의 구성원이어야 하고, 워크스페이스 전체에는 "
                    + "참여자·열람자까지만 부여할 수 있습니다.")
    public ResourceAccessGrantView addLlmKeyAccessGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Valid @RequestBody AddResourceAccessGrantRequest request,
            HttpServletRequest httpRequest) {
        return service.add(principal, ResourceType.LLM_API_KEY, keyId, request,
                clientIp(httpRequest));
    }

    @PatchMapping("/{grantId}")
    @RequireReauth
    @Operation(summary = "접근 권한 등급 변경")
    public ResourceAccessGrantView updateLlmKeyAccessGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @PathVariable UUID grantId,
            @Valid @RequestBody UpdateResourceAccessGrantRequest request,
            HttpServletRequest httpRequest) {
        return service.update(principal, ResourceType.LLM_API_KEY, keyId, grantId, request,
                clientIp(httpRequest));
    }

    @DeleteMapping("/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireReauth
    @Operation(summary = "접근 권한 회수",
            description = "회수해도 발급 시 이미 확인한 키 평문은 회수되지 않습니다. "
                    + "필요하면 키를 재발급해 주세요.")
    public void removeLlmKeyAccessGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @PathVariable UUID grantId,
            HttpServletRequest httpRequest) {
        service.remove(principal, ResourceType.LLM_API_KEY, keyId, grantId, clientIp(httpRequest));
    }
}
