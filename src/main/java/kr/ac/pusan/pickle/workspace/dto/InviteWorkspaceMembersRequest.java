package kr.ac.pusan.pickle.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Contract: POST /workspaces/{workspaceId}/invitations body. */
public record InviteWorkspaceMembersRequest(
        @Schema(description = "초대할 사람 목록. 1명에서 200명까지이며, 한 항목에는 이메일과 학번 중 하나만 씁니다.")
        @NotNull(message = "초대할 사람을 한 명 이상 입력해 주세요.")
        @Size(min = 1, max = 200, message = "한 번에 1명에서 200명까지 초대할 수 있습니다.")
        @Valid
        List<WorkspaceInvitationEntry> entries) {

    /** Contract schema {@code WorkspaceInvitationEntry}: one person, by email or by 학번. */
    public record WorkspaceInvitationEntry(
            @Schema(description = "초대할 사람의 이메일. 학번과 함께 쓸 수 없습니다.",
                    example = "student@pusan.ac.kr")
            @Size(max = 254, message = "이메일은 254자 이하여야 합니다.")
            @Nullable String email,

            @Schema(description = "초대할 사람의 학번. 학번은 학생 직책 계정에만 등록되므로 교수·연구원·직원은 "
                    + "이메일로 초대합니다. 이메일과 함께 쓸 수 없습니다.", example = "202612345")
            @Nullable String studentNo) {
    }
}
