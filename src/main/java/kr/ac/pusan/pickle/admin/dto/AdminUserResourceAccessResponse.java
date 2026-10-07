package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;

/** Secret-free structural access explanation for a named account and resource. */
public record AdminUserResourceAccessResponse(
        ResourceType type, UUID id, String name, String status,
        UUID orgId, String orgName, UUID workspaceId, String workspaceName,
        @Nullable UUID requestId, UserStatus userStatus,
        @Nullable WorkspaceMemberRole workspaceRole,
        @Nullable ResourceRole personalGrantRole,
        @Nullable ResourceRole workspaceGrantRole,
        @Nullable ResourceRole effectiveRole, boolean standingRights,
        @Schema(description = "활성 계정, 소유 워크스페이스 구성원과 리소스 부여의 공통 조건만 충족했는지. "
                + "종류별 작업이나 실제 연결 성공을 보장하지 않습니다.")
        boolean baseConditionsSatisfied,
        @Schema(description = "현재 관리자가 기존 일괄 접근 변경으로 이 사용자의 개인 부여를 회수할 수 있는지")
        boolean canRevoke,
        @Schema(description = "회수 조건. ALLOWED, FORBIDDEN, RESOURCE_NOT_FOUND, NO_PERSONAL_GRANT. "
                + "기존 개인 부여의 회수는 계정 비활성이나 구성원 해제 이후에도 가능합니다.")
        String revokeReason,
        @Schema(description = "공통 진단 사유. ACCOUNT_INACTIVE, NOT_WORKSPACE_MEMBER, NO_RESOURCE_GRANT, "
                + "RESOURCE_ENDED, RESOURCE_NOT_READY. 종류별 상세 조건은 별도 확인이 필요합니다.")
        List<String> reasons) {
}
