package kr.ac.pusan.pickle.workspace.dto;

import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;

/** Contract: PATCH /workspaces/{workspaceId}/members/{userId} body. */
public record UpdateWorkspaceMemberRequest(
        @NotNull(message = "변경할 역할을 지정해 주세요.")
        WorkspaceMemberRole role) {
}
