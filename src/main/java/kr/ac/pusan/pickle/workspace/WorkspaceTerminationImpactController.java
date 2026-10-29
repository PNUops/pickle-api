package kr.ac.pusan.pickle.workspace;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceTerminationImpactResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceTerminationImpactController {
    private final WorkspaceTerminationImpactService impacts;

    public WorkspaceTerminationImpactController(WorkspaceTerminationImpactService impacts) { this.impacts = impacts; }

    @GetMapping("/{workspaceId}/termination-impact")
    @Operation(summary = "워크스페이스 삭제 영향 조회", description = "현재 OWNER가 전체 워크스페이스의 신청 취소, 대상자 자동 종료와 남은 자원 차단 조건을 조회합니다. 조회는 상태를 바꾸지 않으며, 결과는 페이지와 무관한 전체 집합을 기준으로 계산합니다.")
    public WorkspaceTerminationImpactResponse getWorkspaceTerminationImpact(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID workspaceId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "0") int resourcePage) {
        return impacts.workspace(actor, workspaceId, false, page, size, resourcePage);
    }

    @GetMapping("/{workspaceId}/invitations/{invitationId}/termination-impact")
    @Operation(summary = "초대 취소 영향 조회", description = "현재 OWNER가 정확한 초대에 연결된 신청 대상자의 자동 종료 영향을 조회합니다. 신청 자체와 다른 초대는 취소하지 않으며, FAILED는 자동 종료되지 않는 이력으로 구분합니다.")
    public WorkspaceTerminationImpactResponse getWorkspaceInvitationTerminationImpact(@AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID workspaceId, @PathVariable UUID invitationId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "0") int resourcePage) {
        return impacts.invitation(actor, workspaceId, invitationId, false, page, size, resourcePage);
    }
}
