package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import kr.ac.pusan.pickle.admin.dto.AdminPerformanceOverviewResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/performance")
@PreAuthorize("hasAnyRole('SYS_MANAGER', 'SYS_ADMIN')")
public class AdminPerformanceController {
    private final AdminPerformanceOverviewService service;

    public AdminPerformanceController(AdminPerformanceOverviewService service) { this.service = service; }

    @GetMapping("/overview")
    @Operation(operationId = "getAdminPerformanceOverview", summary = "관리자 플랫폼 활용 규모",
            description = "자동 수집한 최신 장비 사양을 읽어요. SYS_ADMIN은 현재 계정과 워크스페이스, 승인 신청과 등록 이력의 전역 집계도 읽어요.")
    public AdminPerformanceOverviewResponse overview(@AuthenticationPrincipal AuthenticatedUser actor) {
        return service.get(actor);
    }
}
