package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminResourceDeadlineKind;
import kr.ac.pusan.pickle.admin.dto.AdminResourceDeadlineResponse;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/resource-deadlines")
@PreAuthorize("hasAnyRole('ORG_VIEWER','ORG_MANAGER','ORG_ADMIN','SYS_VIEWER','SYS_MANAGER','SYS_ADMIN')")
public class AdminResourceDeadlineController {
    private final AdminResourceDeadlineQueryService query;
    public AdminResourceDeadlineController(AdminResourceDeadlineQueryService query) { this.query = query; }

    @GetMapping
    @Operation(summary = "관리자 통합 자원 기한 목록", description = "전역 지원 메타데이터에서 자원마다 주 기한 하나를 조회해요. 기관과 워크스페이스는 선택 필터이며 없는 식별자는 빈 목록을 반환해요. "
            + "VM은 사용 종료일, LLM 키는 만료 시각, 외부 도메인은 갱신 기한 또는 해제 뒤 예약 종료 시각, GPU는 대기 중 승인 종료일 또는 할당이나 반납 중 실제 임대 종료 시각이에요. "
            + "종료 상태와 주 기한이 없는 자원은 제외해요. expiringInDays는 1~365의 KST 달력 창이며 오늘부터 오늘+N일까지예요. "
            + "VM과 대기 GPU는 종료일 양끝을 포함해요. LLM 키는 관측 시각보다 미래이면서 오늘+N 다음 날 0시(KST) 이하인 만료 시각을 포함해요. "
            + "도메인과 GPU 임대의 정확한 기한은 관측 시각보다 미래이면서 같은 다음 날 0시 미만이에요. expired=true는 종료일이 오늘보다 이르거나 정확한 기한이 관측 시각 이하인 자원을 뜻해요. "
            + "expired=true와 expiringInDays를 함께 지정하면 교집합이므로 빈 목록이에요. 관측 시각과 count, 페이지는 같은 DB snapshot을 기준으로 하며 기한 경과를 외부 완료로 해석하지 않아요. 실제 변경 권한은 기존 단건 경로의 기관 범위를 따라요.")
    public PageResponse<AdminResourceDeadlineResponse> listAdminResourceDeadlines(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) ResourceType type,
            @RequestParam(required = false) AdminResourceDeadlineKind deadlineKind,
            @RequestParam(required = false) @Min(1) @Max(365) Integer expiringInDays,
            @RequestParam(required = false) Boolean expired,
            @Parameter(schema = @Schema(allowableValues = {"deadlineAt", "-deadlineAt"})) @RequestParam(defaultValue = "deadlineAt") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return query.list(actor, orgId, workspaceId, type, deadlineKind, expiringInDays, expired, sort, page, size);
    }
}
