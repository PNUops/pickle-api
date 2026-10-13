package kr.ac.pusan.pickle.admin.bulk;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeApplyResponse;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeOperationResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            description = "여러 대상에 한 가지 변경을 적용하면 어떻게 되는지 대상마다 답합니다. 미리보기 기록만 저장하며 대상은 변경하지 않습니다. "
                    + "범위 밖이거나 역할이 허용하지 않는 대상, 현재 상태에서 할 수 없는 대상은 이유와 함께 "
                    + "applicable=false로, 이미 그 값인 대상은 바뀔 필드 없이 applicable=true로 답합니다. "
                    + "대상마다 fingerprint를 주며 적용 요청에 그대로 돌려보냅니다. 대상은 최대 200개입니다.")
    @ApiResponse(responseCode = "200", description = "대상별 판정과 fingerprint")
    public AdminBulkChangePreviewResponse previewAdminBulkChange(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AdminBulkChangeRequest request) {
        return service.preview(principal, request);
    }

    @PostMapping
    @Operation(summary = "관리자 일괄 변경 적용",
            description = "저장된 미리보기와 같은 변경·대상·fingerprint에 previewId와 클라이언트가 한 번 생성한 "
                    + "operationId를 더해 적용합니다. operationId는 결과의 batchId와 같습니다. 새 적용은 미리보기 저장 후 "
                    + "24시간까지만 허용합니다. 같은 수행자가 같은 식별자·본문으로 재시도하면 현재 접근 범위로 가린 "
                    + "저장 결과만 반환하며 변경이나 후속 작업을 다시 실행하지 않습니다. 이 재시도는 미리보기 만료 뒤에도 "
                    + "가능합니다. 같은 수행자의 식별자에 다른 본문을 결합하면 409, 타인의 기록은 404로 거절합니다. "
                    + "새 적용은 대상마다 다시 판정하고 바뀐 확인값은 STALE, 업무상 거절은 SKIPPED로 저장합니다. "
                    + "예기치 않은 트랜잭션 오류는 전체 변경과 결과를 롤백합니다. 커밋 후 callback 오류는 이미 커밋된 "
                    + "결과를 되돌리지 않으며 effects에서 관측합니다. APPLIED는 DB 커밋을 뜻하고 외부 완료를 보장하지 "
                    + "않습니다. 대상별 감사 기록은 단일 변경과 같은 이름으로 남고 같은 batchId를 담습니다.")
    @ApiResponse(responseCode = "200", description = "대상별 결과와 이 적용의 batchId")
    public AdminBulkChangeApplyResponse applyAdminBulkChange(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AdminBulkChangeRequest request,
            HttpServletRequest httpRequest) {
        return service.apply(principal, request, clientIp(httpRequest));
    }
    @GetMapping("/previews/{previewId}")
    @Operation(summary = "저장된 일괄 변경 미리보기 조회", description = "본인의 미리보기를 현재 대상별 변경 권한으로 다시 조회합니다. 접근할 수 없는 대상의 이름, 값, 확인값과 변경 내용은 가립니다.")
    public AdminBulkChangePreviewResponse getAdminBulkChangePreview(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID previewId) { return service.savedPreview(principal, previewId); }

    @GetMapping("/operations/{operationId}")
    @Operation(summary = "저장된 일괄 변경 결과 조회", description = "본인의 커밋된 적용 결과와 callback 접수 관측을 조회합니다. 재실행하지 않으며 DB 커밋, queue 접수와 외부 완료는 별개입니다.")
    public AdminBulkChangeOperationResponse getAdminBulkChangeOperation(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID operationId) { return service.operation(principal, operationId); }

    @GetMapping("/operations")
    @Operation(summary = "본인의 최근 일괄 변경 결과 조회", description = "본인의 최근 커밋 결과 최대 20개를 현재 접근 범위로 가려서 조회합니다. 대상과 후속 작업을 변경하지 않습니다.")
    public List<AdminBulkChangeOperationResponse> listAdminBulkChangeOperations(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.recent(principal);
    }

}
