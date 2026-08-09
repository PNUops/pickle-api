package kr.ac.pusan.pickle.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;

/** Contract: POST /workspaces/{workspaceId}/members body. */
public record AddWorkspaceMemberRequest(
        @NotBlank(message = "추가할 사용자의 이메일을 입력해 주세요.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @NotNull(message = "부여할 역할을 지정해 주세요.")
        WorkspaceMemberRole role) {
}
