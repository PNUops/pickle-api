package kr.ac.pusan.pickle.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationOutcome;
import org.jspecify.annotations.Nullable;

/** Contract: POST /workspaces/{workspaceId}/invitations response. */
public record InviteWorkspaceMembersResponse(
        @Schema(description = "요청한 항목마다 한 건씩, 요청과 같은 순서로 반환해요.")
        List<WorkspaceInvitationResult> results) {

    /** Contract schema {@code WorkspaceInvitationResult}: what one entry came to. */
    public record WorkspaceInvitationResult(
            @Schema(description = "정규화한 요청 이메일이며, 학번 항목이면 null이에요.")
            @Nullable String email,

            @Schema(description = "앞뒤 공백을 지운 요청 학번이며, 이메일 항목이면 null이에요.")
            @Nullable String studentNo,

            @Schema(description = "처리 결과.")
            WorkspaceInvitationOutcome outcome,

            @Schema(description = "대기 중인 초대 ID예요. INVITED나 ALREADY_INVITED일 때만 있어요.")
            @Nullable UUID invitationId,

            @Schema(description = "구성원 계정의 공개 ID예요. ADDED나 ALREADY_MEMBER일 때만 있어요.")
            @Nullable UUID userId) {
    }
}
