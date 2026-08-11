package kr.ac.pusan.pickle.llm;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.RequireReauth;
import kr.ac.pusan.pickle.llm.dto.IssuedLlmKeyResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyDetailResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeySummaryResponse;
import kr.ac.pusan.pickle.llm.dto.UpdateLlmKeyRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code llm-keys} (server /api/v1). */
@Tag(name = "llm-keys", description = "LLM API 키 — 워크스페이스가 보유한 키의 목록과 상세입니다. "
        + "키 평문은 발급 시 한 번만 표시되며 여기서는 다시 볼 수 없습니다.")
@RestController
@RequestMapping("/api/v1/llm-keys")
public class LlmKeyController {

    private final LlmApiKeyQueryService queryService;
    private final LlmApiKeyService keyService;

    public LlmKeyController(LlmApiKeyQueryService queryService, LlmApiKeyService keyService) {
        this.queryService = queryService;
        this.keyService = keyService;
    }

    @GetMapping
    @Operation(summary = "LLM API 키 목록",
            description = "내가 속한 워크스페이스의 키를 보여 줍니다. 접근 권한이 없는 키는 "
                    + "이름·상태·소유자만 담긴 제한된 행으로 표시됩니다.")
    public PageResponse<LlmKeySummaryResponse> listLlmKeys(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queryService.list(principal, workspaceId, page, size);
    }

    @GetMapping("/{keyId}")
    @Operation(summary = "LLM API 키 상세",
            description = "접근 권한이 있는 키의 상세입니다. 키 평문과 그 해시는 어떤 응답에도 담기지 않습니다.")
    public LlmKeyDetailResponse getLlmKey(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        return queryService.get(principal, keyId);
    }

    @PostMapping("/{keyId}/token")
    @RequireReauth
    @Operation(summary = "LLM API 키 발급",
            description = "이 키의 평문을 만들어 **한 번만** 돌려줍니다. 서버에는 해시만 남으므로 "
                    + "다시 조회할 수 없고, 분실하면 이 호출을 다시 해서 재발급해야 합니다. "
                    + "재발급하면 이전 값은 곧바로 쓸 수 없게 됩니다.")
    public IssuedLlmKeyResponse issueLlmKeyToken(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        return keyService.issue(principal, keyId);
    }

    @PostMapping("/{keyId}/revoke")
    @RequireReauth
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "LLM API 키 폐기",
            description = "이 키를 폐기합니다. 게이트웨이에는 폴링 주기 안에 반영되고, 이후 이 키로 "
                    + "보낸 요청은 '폐기된 키'로 거부됩니다. 사용 기록은 남습니다.")
    public void revokeLlmKey(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        keyService.revoke(principal, keyId);
    }

    @PatchMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "LLM API 키 수정",
            description = "키 이름과 사용 목적, 본문 기록 여부를 바꿉니다. 생략한 항목은 그대로 둡니다.")
    public void updateLlmKey(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId, @Valid @RequestBody UpdateLlmKeyRequest form) {
        keyService.update(principal, keyId, form);
    }
}
