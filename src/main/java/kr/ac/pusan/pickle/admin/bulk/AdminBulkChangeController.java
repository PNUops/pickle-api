package kr.ac.pusan.pickle.admin.bulk;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeApplyResponse;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangePreviewResponse;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, bulk changes. The class gate admits the four
 * roles that may write anything at all; what each may do to each target is
 * decided per target by the same rules as the single path, and reported per
 * target rather than as a refusal of the whole call.
 */
@Tag(name = "admin", description = "관리자 API")
@RestController
@RequestMapping("/api/v1/admin/bulk-changes")
@PreAuthorize("hasAnyRole('ORG_MANAGER', 'ORG_ADMIN', 'SYS_MANAGER', 'SYS_ADMIN')")
public class AdminBulkChangeController {

    private final AdminBulkChangeService service;

    public AdminBulkChangeController(AdminBulkChangeService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    @Operation(summary = "관리자 일괄 변경 미리보기",
            description = "여러 대상에 한 가지 변경을 적용하면 어떻게 되는지 대상마다 답합니다. 아무것도 쓰지 않습니다. "
                    + "범위 밖이거나 역할이 허용하지 않는 대상, 현재 상태에서 할 수 없는 대상은 이유와 함께 "
                    + "applicable=false로, 이미 그 값인 대상은 바뀔 필드 없이 applicable=true로 답합니다. "
                    + "대상마다 fingerprint를 주며 적용 요청에 그대로 돌려보냅니다. 대상은 최대 200개입니다.")
    public AdminBulkChangePreviewResponse previewAdminBulkChange(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AdminBulkChangeRequest request) {
        return service.preview(principal, request);
    }

    @PostMapping
    @Operation(summary = "관리자 일괄 변경 적용",
            description = "미리보기와 같은 본문에 fingerprint를 더해 적용합니다. 서버는 미리보기를 믿지 않고 적용 시점에 "
                    + "대상마다 다시 판정하며, 미리보기 뒤 바뀐 대상은 STALE로 답하고 쓰지 않습니다. 한 대상의 실패가 "
                    + "다른 대상을 되돌리지 않고, 되돌리기는 없습니다. 대상별 감사 기록은 단일 변경과 같은 이름으로 "
                    + "남고 같은 batchId를 담습니다.")
    public AdminBulkChangeApplyResponse applyAdminBulkChange(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AdminBulkChangeRequest request,
            HttpServletRequest httpRequest) {
        return service.apply(principal, request, clientIp(httpRequest));
    }
}
