package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.user.UserStatus;

/**
 * Contract {@code AdminWorkspaceMember} (v0.19.0): a workspace member in the admin
 * inspection view. Unlike the user-facing member list, every member is listed
 * regardless of account status — suspended/withdrawn members are exactly what
 * an admin audit needs to see — with {@code userStatus} carrying the state.
 */
public record AdminWorkspaceMemberResponse(
        UUID userId,
        String name,
        @org.jspecify.annotations.Nullable String email,
        WorkspaceMemberRole workspaceRole,
        UserStatus userStatus,
        @Schema(description = "현재 관리자에게 이 구성원의 사용자 관계·지원 상세를 열람할 권한이 있는지 여부")
        boolean supportDetailVisible,
        @org.jspecify.annotations.Nullable Instant joinedAt,
        boolean canChangeRole,
        boolean canRemove) {
    public AdminWorkspaceMemberResponse(UUID userId, String name, String email, WorkspaceMemberRole workspaceRole,
            UserStatus userStatus, boolean supportDetailVisible, Instant joinedAt) {
        this(userId, name, email, workspaceRole, userStatus, supportDetailVisible, joinedAt, false, false);
    }
}
