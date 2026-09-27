package kr.ac.pusan.pickle.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code WorkspaceInvitation}: one open invitation as its
 * owner sees it. The email or 학번 is shown back because the owner typed it.
 */
public record WorkspaceInvitationResponse(
        @Schema(description = "초대 식별자")
        UUID id,

        @Schema(description = "초대한 이메일. 학번으로 초대했으면 null입니다.")
        @Nullable String email,

        @Schema(description = "초대한 학번. 이메일로 초대했으면 null입니다.")
        @Nullable String studentNo,

        @Schema(description = "구성원이 되었을 때 받을 역할")
        WorkspaceMemberRole role,

        @Schema(description = "초대한 시각")
        Instant invitedAt,

        @Schema(description = "초대한 사람")
        WorkspaceInvitationInviter invitedBy) {

    /** Contract schema {@code WorkspaceInvitationInviter}. */
    public record WorkspaceInvitationInviter(
            @Schema(description = "계정 공개 식별자")
            UUID id,

            @Schema(description = "이름")
            String name) {
    }
}
