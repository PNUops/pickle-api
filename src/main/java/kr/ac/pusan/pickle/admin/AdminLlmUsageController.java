package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminLlmPerformanceResponse;
import kr.ac.pusan.pickle.admin.dto.AdminLlmUsageResponse;
import kr.ac.pusan.pickle.admin.dto.LlmConsumerSort;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
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

    @GetMapping("/performance")
    @Operation(operationId = "getAdminLlmPerformance", summary = "관리자 LLM 활용 성과",
            description = "권한 범위에 보관 중인 요청과 토큰, 이용 기관과 워크스페이스, 키 수와 추이를 읽어요. "
                    + "기간을 생략하면 보관 중인 전체 일별 기록을 집계해요. 개인 이용자 수와 비용은 제공하지 않아요.")
    public AdminLlmPerformanceResponse performance(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID workspaceId,
            @Parameter(description = "7, 30, 90 중 선택해요. 생략하면 전체 기간이에요.")
            @RequestParam(required = false) @Nullable Integer days) {
        return service.performance(principal, orgId, workspaceId, days);
    }

    @GetMapping
    @Operation(operationId = "getAdminLlmUsage", summary = "관리자 LLM 사용량 통계",
            description = "DB에 저장된 rollup과 usage event, vendor meter cache, gateway "
                    + "자기보고를 읽어 수요와 소비처, 한도 검토, 데이터 신뢰도를 제공해요. "
                    + "조회 과정에서 gateway나 vendor를 직접 호출하지 않아요.")
    public AdminLlmUsageResponse usage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "조회할 기관이에요. 기관 계층에서는 맡은 기관만 지정할 수 있어요.")
            @RequestParam(required = false) UUID orgId,
            @Parameter(description = "소비처를 key 단계로 좁힐 workspace 공개 ID")
            @RequestParam(required = false) UUID workspaceId,
            @Parameter(description = "일별 추이와 소비처 기간. 7, 30, 90 중 하나")
            @RequestParam(defaultValue = "7") int days,
            @Parameter(description = "소비처와 한도 검토가 각각 반환할 최대 행 수")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int top,
            @RequestParam(defaultValue = "0") @Min(0) int consumerPage,
            @RequestParam(required = false) @Min(1) @Max(100) Integer consumerSize,
            @RequestParam(defaultValue = "REQUESTS_DESC") LlmConsumerSort consumerSort,
            @RequestParam(defaultValue = "0") @Min(0) int reviewPage,
            @RequestParam(required = false) @Min(1) @Max(100) Integer reviewSize) {
        return service.get(principal, orgId, workspaceId, days, top, consumerPage,
                consumerSize == null ? top : consumerSize, consumerSort, reviewPage,
                reviewSize == null ? top : reviewSize);
    }
}
