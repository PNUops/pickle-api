package kr.ac.pusan.pickle.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationOutcome;
import org.jspecify.annotations.Nullable;

/** Contract: POST /workspaces/{workspaceId}/invitations response. */
public record InviteWorkspaceMembersResponse(
        @Schema(description = "요청 항목마다 한 건씩, 요청과 같은 순서입니다.")
        List<WorkspaceInvitationResult> results) {

    /** Contract schema {@code WorkspaceInvitationResult}: what one entry came to. */
    public record WorkspaceInvitationResult(
            @Schema(description = "요청에 쓴 이메일(정규화한 값). 학번 항목이면 null입니다.")
            @Nullable String email,

            @Schema(description = "요청에 쓴 학번(앞뒤 공백 제거). 이메일 항목이면 null입니다.")
            @Nullable String studentNo,

            @Schema(description = "처리 결과.")
            WorkspaceInvitationOutcome outcome,

            @Schema(description = "대기 중인 초대의 식별자. INVITED와 ALREADY_INVITED일 때만 있습니다.")
            @Nullable UUID invitationId,

            @Schema(description = "구성원이 된 계정의 공개 식별자. ADDED일 때만 있습니다.")
            @Nullable UUID userId) {
    }
}
