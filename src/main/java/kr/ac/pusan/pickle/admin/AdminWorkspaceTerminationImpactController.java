package kr.ac.pusan.pickle.admin;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.workspace.WorkspaceTerminationImpactService;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationImpactResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/workspaces")
@PreAuthorize("hasAnyRole('ORG_VIEWER', 'ORG_MANAGER', 'ORG_ADMIN', 'SYS_ADMIN')")
public class AdminWorkspaceTerminationImpactController {
    private final WorkspaceTerminationImpactService impacts;

    public AdminWorkspaceTerminationImpactController(WorkspaceTerminationImpactService impacts) { this.impacts = impacts; }

    @GetMapping("/{workspaceId}/termination-impact")
    @Operation(summary = "지원 워크스페이스 삭제 영향 조회", description = "기존 지원 쓰기 권한으로 전체 워크스페이스의 신청 취소, 대상자 자동 종료와 남은 자원 차단 조건을 조회합니다. 기관 선택은 전체 영향 집합을 좁히지 않습니다. 조회는 가입, 생성 또는 취소를 실행하지 않습니다.")
    public WorkspaceTerminationImpactResponse getAdminWorkspaceTerminationImpact(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID workspaceId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "0") int resourcePage) {
        return impacts.workspace(actor, workspaceId, true, page, size, resourcePage);
    }

    @GetMapping("/{workspaceId}/invitations/{invitationId}/termination-impact")
    @Operation(summary = "지원 초대 취소 영향 조회", description = "기존 지원 쓰기 권한으로 정확한 초대에 연결된 신청 대상자의 종료 영향을 조회합니다. 복수 기관의 신청을 포함하며 FAILED는 자동 종료되지 않는 이력으로 구분합니다.")
    public WorkspaceTerminationImpactResponse getAdminWorkspaceInvitationTerminationImpact(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID workspaceId, @PathVariable UUID invitationId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "0") int resourcePage) {
        return impacts.invitation(actor, workspaceId, invitationId, true, page, size, resourcePage);
    }
}
