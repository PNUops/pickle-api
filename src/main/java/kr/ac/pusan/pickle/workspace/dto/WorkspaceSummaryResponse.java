package kr.ac.pusan.pickle.workspace.dto;

import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import java.util.UUID;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code WorkspaceSummary} (my-workspaces list). */
public record WorkspaceSummaryResponse(
        UUID id,
        WorkspaceKind kind,
        String name,
        @Nullable String description,
        WorkspaceMemberRole myRole,
        long memberCount) {

    public static WorkspaceSummaryResponse from(WorkspaceMember membership, long memberCount) {
        var workspace = membership.getWorkspace();
        return new WorkspaceSummaryResponse(workspace.getPublicId(), workspace.getKind(), workspace.getName(),
                workspace.getDescription(), membership.getRole(), memberCount);
    }
}
