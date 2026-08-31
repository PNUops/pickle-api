package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminLlmUsageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scope-aware administrator demand, consumers, limit review and data quality. */
@Tag(name = "admin", description = "관리자 API")
@RestController
@RequestMapping("/api/v1/admin/llm/usage")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', "
        + "'SYS_VIEWER', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminLlmUsageController {

    private final AdminLlmUsageService service;

    public AdminLlmUsageController(AdminLlmUsageService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "getAdminLlmUsage", summary = "관리자 LLM 사용량 통계",
            description = "DB에 저장된 rollup과 usage event, vendor meter cache, gateway "
                    + "자기보고를 읽어 수요·소비처·한도 검토·데이터 신뢰도를 제공합니다. "
                    + "조회 과정에서 gateway나 vendor를 직접 호출하지 않습니다.")
    public AdminLlmUsageResponse usage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "조회할 기관. 기관 계층은 맡은 기관만 지정할 수 있습니다.")
            @RequestParam(required = false) UUID orgId,
            @Parameter(description = "소비처를 key 단계로 좁힐 workspace 공개 ID")
            @RequestParam(required = false) UUID workspaceId,
            @Parameter(description = "일별 추이와 소비처 기간. 7, 30, 90 중 하나")
            @RequestParam(defaultValue = "7") int days,
            @Parameter(description = "소비처와 한도 검토가 각각 반환할 최대 행 수")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int top) {
        return service.get(principal, orgId, workspaceId, days, top);
    }
}
