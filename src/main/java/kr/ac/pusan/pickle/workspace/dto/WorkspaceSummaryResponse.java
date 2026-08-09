package kr.ac.pusan.pickle.workspace.dto;

import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code WorkspaceSummary} (my-workspaces list). */
public record WorkspaceSummaryResponse(
        Long id,
        WorkspaceKind kind,
        String name,
        String slug,
        @Nullable String description,
        WorkspaceMemberRole myRole,
        long memberCount) {

    public static WorkspaceSummaryResponse from(WorkspaceMember membership, long memberCount) {
        var workspace = membership.getWorkspace();
        return new WorkspaceSummaryResponse(workspace.getId(), workspace.getKind(), workspace.getName(), workspace.getSlug(),
                workspace.getDescription(), membership.getRole(), memberCount);
    }
}
