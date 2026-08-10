package kr.ac.pusan.pickle.workspace.dto;

import java.util.UUID;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.user.User;

/** Contract schema {@code WorkspaceMember}. */
public record WorkspaceMemberResponse(UUID userId, String name, String email, WorkspaceMemberRole role) {

    public static WorkspaceMemberResponse from(WorkspaceMember member, User user) {
        return new WorkspaceMemberResponse(user.getPublicId(), user.getName(), user.getEmail(),
                member.getRole());
    }
}
