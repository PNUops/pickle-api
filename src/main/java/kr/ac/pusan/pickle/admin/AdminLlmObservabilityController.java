package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.LlmMetricsResponse;
import kr.ac.pusan.pickle.admin.dto.LlmStatusResponse;
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

    public AdminLlmObservabilityController(AdminLlmObservabilityService service) {
        this.service = service;
    }

    @GetMapping("/status")
    @Operation(operationId = "getAdminLlmStatus", summary = "관리자 LLM 서비스 상태",
            description = "게이트웨이 자기보고와 upstream별 passive·active·catalog 현재 상태를 조회합니다. "
                    + "조회 과정에서 gateway나 upstream을 직접 호출하지 않으며 변경 작업도 제공하지 않습니다.")
    public LlmStatusResponse status(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "조회할 기관. 기관 계층은 맡은 기관만 지정할 수 있습니다.")
            @RequestParam(required = false) UUID orgId) {
        return service.status(principal, orgId);
    }

    @GetMapping("/metrics")
    @Operation(operationId = "getAdminLlmMetrics", summary = "관리자 LLM upstream 지표",
            description = "raw usage event에서 마지막 처리 upstream별 최종 결과와 지연, token, "
                    + "attempt coverage를 집계합니다. 중간 retry 경로나 uptime을 뜻하지 않습니다.")
    public LlmMetricsResponse metrics(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "조회할 기관. 기관 계층은 맡은 기관만 지정할 수 있습니다.")
            @RequestParam(required = false) UUID orgId,
            @Parameter(description = "진단 기간(일), 최대 31일")
            @RequestParam(defaultValue = "7") @Min(1) @Max(31) int days) {
        return service.metrics(principal, orgId, days);
    }
}
