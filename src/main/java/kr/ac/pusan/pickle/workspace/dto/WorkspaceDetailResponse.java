package kr.ac.pusan.pickle.workspace.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code WorkspaceDetail} (workspace with members + my role in it). */
public record WorkspaceDetailResponse(
        Long id,
        WorkspaceKind kind,
        String name,
        String slug,
        @Nullable String description,
        WorkspaceMemberRole myRole,
        List<WorkspaceMemberResponse> members,
        Instant createdAt) {

    public static WorkspaceDetailResponse from(Workspace workspace, WorkspaceMemberRole myRole,
            List<WorkspaceMemberResponse> members) {
        return new WorkspaceDetailResponse(workspace.getId(), workspace.getKind(), workspace.getName(), workspace.getSlug(),
                workspace.getDescription(), myRole, members, workspace.getCreatedAt());
    }
}
