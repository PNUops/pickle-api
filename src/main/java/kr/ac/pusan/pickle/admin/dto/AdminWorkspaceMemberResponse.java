package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.user.UserStatus;

/**
 * Contract {@code AdminWorkspaceMember} (v0.19.0): a workspace member in the admin
 * inspection view. Unlike the user-facing member list, every member is listed
 * regardless of account status — suspended/withdrawn members are exactly what
 * an admin audit needs to see — with {@code userStatus} carrying the state.
 */
public record AdminWorkspaceMemberResponse(
        long userId,
        String name,
        String email,
        WorkspaceMemberRole workspaceRole,
        UserStatus userStatus,
        Instant joinedAt) {
}
