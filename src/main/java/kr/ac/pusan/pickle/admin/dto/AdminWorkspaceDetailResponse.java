package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code AdminWorkspaceDetail} (v0.19.0): admin inspection view of one
 * workspace. {@code memberCount} keeps the option-list definition (ACTIVE members
 * = announcement fan-out basis) while {@code members} lists everyone; there is
 * deliberately no single org field — derived org membership is many-to-many
 * (a workspace belongs to every org it has requests/VMs in).
 */
public record AdminWorkspaceDetailResponse(
        long id,
        WorkspaceKind kind,
        String name,
        @Nullable String description,
        Instant createdAt,
        long memberCount,
        long vmCount,
        List<AdminWorkspaceMemberResponse> members) {
}
