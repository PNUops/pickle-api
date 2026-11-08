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
@Tag(name = "llm-keys", description = "워크스페이스가 보유한 LLM API 키의 목록과 상세예요. "
        + "키 평문은 발급할 때 한 번만 보여 주며 여기서는 다시 볼 수 없어요.")
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
            description = "`workspaceId`를 지정하지 않으면 내가 접근 권한을 가진 키만 "
                    + "조회해요. 지정하면 해당 워크스페이스의 키 목록을 조회하며 접근 권한이 없는 키는 "
                    + "이름과 상태, 소유자만 담긴 제한된 행으로 표시해요.")
    public PageResponse<LlmKeySummaryResponse> listLlmKeys(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queryService.list(principal, workspaceId, page, size);
    }

    @GetMapping("/{keyId}")
    @Operation(summary = "LLM API 키 상세",
            description = "접근 권한이 있는 키의 상세를 조회해요. 상세 응답에는 키 평문과 해시를 담지 않아요.")
    public LlmKeyDetailResponse getLlmKey(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        return queryService.get(principal, keyId);
    }

    @GetMapping("/{keyId}/models")
    @Operation(summary = "LLM API 키로 호출할 수 있는 모델",
            description = "이 키에서 선택할 모델 후보예요. 자체 서빙 모델은 플랫폼 카탈로그에서, "
                    + "유료 모델은 공급자 목록의 캐시에서 가져와요. 키에 모델 허용 목록이 있으면 "
                    + "그 목록에 맞는 것만 담기고, 금액 한도가 아직 없어도 무엇을 신청할지 볼 수 "
                    + "있도록 목록을 제공해요. 최종 판정은 호출 시점에 이루어지므로 여기 있는 "
                    + "모델이 항상 응답한다는 보장은 아니에요.")
    public LlmKeyModelsResponse listLlmKeyModels(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        return modelService.list(principal, keyId);
    }

    @GetMapping("/{keyId}/usage")
    @Operation(summary = "LLM API 키 사용량",
            description = "이 키의 일별 사용량을 조회해요. 하루는 한국 시간 기준이며 호출이 없던 날도 "
                    + "0으로 채워요. Gateway가 배치로 보고하므로 오늘 값은 아직 "
                    + "집계 중이에요.")
    public LlmKeyUsageTrendResponse getLlmKeyUsage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @Parameter(description = "오늘을 포함해 거슬러 올라갈 일수")
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return usageService.trend(principal, keyId, days);
    }

    @GetMapping("/{keyId}/bodies")
    @Operation(summary = "기록된 본문 목록",
            description = "본문 기록을 켠 동안 이 키로 오간 프롬프트와 응답의 목록이에요. "
                    + "각 행에는 앞부분만 담기며 전문은 개별 조회로 확인해요. "
                    + "**이 키에 접근 권한이 있으면 누구나 읽을 수 있어요.** Gateway는 "
                    + "키만 인증해 보낸 사람을 구분하지 못하므로 "
                    + "키 단위로 열람해요. 기록은 30일 동안 보관하고 지난 것부터 지워요. "
                    + "본문 기록을 꺼도 이미 기록된 내용은 보관 기간까지 조회할 수 있어요.")
    public ResponseEntity<PageResponse<LlmKeyBodySummaryResponse>> listLlmKeyBodies(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "한 번에 가져올 개수. 한 건이 최대 320 KiB라 통상 목록보다 "
                    + "상한이 낮아요.")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            HttpServletRequest httpRequest) {
        // no-store on both: the rows carry what somebody typed, and a shared
        // or proxy cache is not a place for it.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bodyService.list(principal, keyId, page, size, clientIp(httpRequest)));
    }

    @GetMapping("/{keyId}/bodies/{bodyId}")
    @Operation(summary = "기록된 본문 상세",
            description = "기록 한 건의 전문이에요. `request`는 보통 보낸 messages 배열 "
                    + "그대로이며 길이 제한에 걸리면 앞부분을 담은 문자열이에요.")
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
    @Operation(summary = "LLM API 키 발급",
            description = "키 평문을 만들어 **한 번만** 반환해요. 서버에는 평문을 저장하지 않으므로 "
                    + "다시 조회할 수 없어요. 잃어버렸다면 이 경로로 재발급해 주세요. "
                    + "재발급한 값이 Gateway에 반영되면 이전 값으로 새 요청을 인증할 수 없어요.")
    public IssuedLlmKeyResponse issueLlmKeyToken(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        return keyService.issue(principal, keyId);
    }

    @PostMapping("/{keyId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "LLM API 키 폐기",
            description = "이 키를 폐기해요. 변경이 Gateway에 반영되면 이 키로 "
                    + "새로 인증하는 요청은 '폐기된 키'로 거절돼요. 사용 기록은 남아요.")
    public void revokeLlmKey(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId) {
        keyService.revoke(principal, keyId);
    }

    @PatchMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "LLM API 키 수정",
            description = "키 이름과 사용 목적, 본문 기록 여부를 바꿔요. 생략한 항목은 그대로 둬요.")
    public void updateLlmKey(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID keyId, @Valid @RequestBody UpdateLlmKeyRequest form) {
        keyService.update(principal, keyId, form);
    }
}
