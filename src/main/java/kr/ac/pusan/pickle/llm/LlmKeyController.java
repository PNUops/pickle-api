package kr.ac.pusan.pickle.llm;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.RequireReauth;
import kr.ac.pusan.pickle.llm.dto.IssuedLlmKeyResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyBodyDetailResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyBodySummaryResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyDetailResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyModelsResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeySummaryResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyUsageTrendResponse;
import kr.ac.pusan.pickle.llm.dto.UpdateLlmKeyRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final LlmKeyUsageService usageService;
    private final LlmKeyBodyService bodyService;
    private final LlmKeyModelService modelService;

    public LlmKeyController(LlmApiKeyQueryService queryService, LlmApiKeyService keyService,
            LlmKeyUsageService usageService, LlmKeyBodyService bodyService,
            LlmKeyModelService modelService) {
        this.queryService = queryService;
        this.keyService = keyService;
        this.usageService = usageService;
        this.bodyService = bodyService;
        this.modelService = modelService;
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

    @GetMapping("/{keyId}/models")
    @Operation(summary = "LLM API 키로 호출할 수 있는 모델",
            description = "이 키로 부를 수 있는 모델입니다. 자체 서빙 모델은 플랫폼 카탈로그에서, "
                    + "유료 모델은 공급자 목록의 캐시에서 옵니다. 키에 모델 허용 목록이 있으면 "
                    + "그 목록에 맞는 것만 담기고, 금액 한도가 아직 없어도 무엇을 신청할지 볼 수 "
                    + "있도록 목록은 채워집니다. 최종 판정은 호출 시점에 이뤄지므로 여기 있는 "
                    + "모델이 항상 응답한다는 보장은 아닙니다.")
    public LlmKeyModelsResponse listLlmKeyModels(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        return modelService.list(principal, keyId);
    }

    @GetMapping("/{keyId}/usage")
    @Operation(summary = "LLM API 키 사용량",
            description = "이 키의 일별 사용량입니다. 하루는 한국 시간 기준이고, 호출이 없던 날도 "
                    + "0으로 채워집니다. 게이트웨이가 배치로 보고하므로 오늘 자 값은 아직 "
                    + "채워지는 중입니다.")
    public LlmKeyUsageTrendResponse getLlmKeyUsage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Parameter(description = "오늘을 포함해 거슬러 올라갈 일수")
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return usageService.trend(principal, keyId, days);
    }

    @GetMapping("/{keyId}/bodies")
    @Operation(summary = "기록된 본문 목록",
            description = "본문 기록을 켠 동안 이 키로 오간 프롬프트와 응답의 목록입니다. "
                    + "각 줄에는 앞부분만 담기고, 전문은 개별 조회로 봅니다. "
                    + "**이 키에 접근 권한이 있는 사람은 모두 읽을 수 있습니다** — 게이트웨이는 "
                    + "키를 인증할 뿐 보낸 사람이 누구인지 알지 못하므로 열람 단위가 사람이 "
                    + "아니라 키입니다. 기록은 30일 동안 보관하고 지난 것부터 지웁니다. "
                    + "본문 기록을 끈 뒤에도 이미 기록된 것은 보관 기간까지 그대로 보입니다.")
    public ResponseEntity<PageResponse<LlmKeyBodySummaryResponse>> listLlmKeyBodies(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "한 번에 가져올 개수. 한 건이 최대 320 KiB라 통상 목록보다 "
                    + "상한이 낮습니다.")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            HttpServletRequest httpRequest) {
        // no-store on both: the rows carry what somebody typed, and a shared
        // or proxy cache is not a place for it.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bodyService.list(principal, keyId, page, size, clientIp(httpRequest)));
    }

    @GetMapping("/{keyId}/bodies/{bodyId}")
    @RequireReauth
    @Operation(summary = "기록된 본문 상세",
            description = "기록 한 건의 전문입니다. `request`는 보통 보낸 messages 배열 "
                    + "그대로이고, 길이 제한에 걸린 경우에는 앞부분을 담은 문자열입니다. "
                    + "저장된 본문을 그대로 돌려주므로 **재인증이 필요합니다** "
                    + "(X-Reauth-Token). 목록 조회에는 필요하지 않습니다.")
    public ResponseEntity<LlmKeyBodyDetailResponse> getLlmKeyBody(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @PathVariable UUID bodyId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bodyService.get(principal, keyId, bodyId, clientIp(httpRequest)));
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
