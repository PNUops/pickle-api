package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code AdminWorkspaceDetail} (v0.19.0): admin inspection view of one
 * workspace. {@code memberCount} keeps the option-list definition (ACTIVE members
 * = announcement fan-out basis) while {@code members} lists everyone; there is
 * deliberately no single org field — derived org membership is many-to-many
 * (a workspace belongs to every org it has requests/VMs in).
 */
public record AdminWorkspaceDetailResponse(
        UUID id,
        WorkspaceKind kind,
        String name,
        @Nullable String description,
        @Nullable Instant createdAt,
        @Nullable Long memberCount,
        @Nullable Long vmCount,
        @Nullable List<AdminWorkspaceMemberResponse> members,
        @Nullable List<RosterCandidate> rosterCandidates,
        boolean canUpdateInfo,
        boolean canInviteMembers,
        boolean canManageMembers,
        boolean canManageOwners,
        boolean canDelete,
        @Nullable String deleteBlockedReason,
        boolean metadataVisible,
        java.util.Set<String> allowedWorkIds) {

    public AdminWorkspaceDetailResponse(UUID id, WorkspaceKind kind, String name, @Nullable String description,
            @Nullable Instant createdAt, @Nullable Long memberCount, @Nullable Long vmCount, List<AdminWorkspaceMemberResponse> members) {
        this(id, kind, name, description, createdAt, memberCount, vmCount, members, null, false, false, false, false, false, null, true, java.util.Set.of());
    }

    public record RosterCandidate(UUID userId, String name, WorkspaceMemberRole role, UserStatus status) { }
}
