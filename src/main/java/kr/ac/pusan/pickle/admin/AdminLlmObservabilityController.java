package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.LlmMetricsResponse;
import kr.ac.pusan.pickle.admin.dto.LlmStatusResponse;
import kr.ac.pusan.pickle.admin.dto.OpenRouterCatalogueResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only administrator observability for the LLM gateway and upstreams. */
@Tag(name = "admin", description = "관리자 API")
@RestController
@RequestMapping("/api/v1/admin/llm")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminLlmObservabilityController {

    private final AdminLlmObservabilityService service;
    private final AdminOpenRouterCatalogueService catalogue;

    public AdminLlmObservabilityController(AdminLlmObservabilityService service,
            AdminOpenRouterCatalogueService catalogue) {
        this.service = service;
        this.catalogue = catalogue;
    }

    @GetMapping("/openrouter-models")
    @Operation(operationId = "listAdminOpenRouterModels", summary = "유료 모델 카탈로그",
            description = "승인 화면에서 고를 수 있는 유료 모델 후보와 가격을 조회해요. "
                    + "캐시만 읽으므로 이 호출이 벤더를 부르지 않으며, 목록이 비어 있거나 오래됐어도 "
                    + "모델 이름을 직접 입력해 승인할 수 있어요. 성공 기록이 없으면 시도하지 않았든 실패했든 "
                    + "UNKNOWN으로 표시돼요. "
                    + "lastError와 consecutiveFailures를 함께 확인해 주세요.")
    public OpenRouterCatalogueResponse openRouterModels(@AuthenticationPrincipal AuthenticatedUser actor) {
        return catalogue.catalogue(actor);
    }

    @GetMapping("/status")
    @Operation(operationId = "getAdminLlmStatus", summary = "관리자 LLM 서비스 상태",
            description = "게이트웨이의 자기보고와 upstream별 passive, active, catalog 현재 상태를 조회해요. "
                    + "현재 범위에서 읽을 수 있는 공급원에 연결된 공개 TOKEN 모델의 주 경로와 대체 경로 구성 관계도 제공해요. "
                    + "전체 모델 목록이나 실제 처리 경로를 보여 주는 것은 아니에요. "
                    + "조회 과정에서 gateway나 upstream을 직접 호출하지 않으며 변경 작업도 제공하지 않아요.")
    public LlmStatusResponse status(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "조회할 기관이에요. 기관 계층에서는 맡은 기관만 지정할 수 있어요.")
            @RequestParam(required = false) UUID orgId) {
        return service.status(principal, orgId);
    }

    @GetMapping("/metrics")
    @Operation(operationId = "getAdminLlmMetrics", summary = "관리자 LLM upstream 지표",
            description = "raw usage event에서 마지막 처리 upstream별 최종 결과와 지연, token, "
                    + "attempt coverage를 집계해요. 중간 retry 경로나 uptime을 뜻하지 않아요.")
    public LlmMetricsResponse metrics(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "조회할 기관이에요. 기관 계층에서는 맡은 기관만 지정할 수 있어요.")
            @RequestParam(required = false) UUID orgId,
            @Parameter(description = "진단 기간(일), 최대 31일")
            @RequestParam(defaultValue = "7") @Min(1) @Max(31) int days) {
        return service.metrics(principal, orgId, days);
    }
}
