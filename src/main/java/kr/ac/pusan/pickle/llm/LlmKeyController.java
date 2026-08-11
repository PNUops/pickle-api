package kr.ac.pusan.pickle.llm;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyDetailResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeySummaryResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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

    public LlmKeyController(LlmApiKeyQueryService queryService) {
        this.queryService = queryService;
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
}
