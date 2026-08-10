package kr.ac.pusan.pickle.user.dto;

import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.consent.dto.TermsVersionView;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code UserProfile}. */
public record UserProfileResponse(
        UUID id,
        String email,
        String name,
        UserRole role,
        @Nullable UUID orgId,
        UserStatus status,
        List<Membership> memberships,
        boolean mfaEnabled,
        List<TermsVersionView> pendingConsents) {

    public record Membership(UUID workspaceId, String workspaceName, WorkspaceKind workspaceKind, WorkspaceMemberRole role) {

        public static Membership from(WorkspaceMember member) {
            return new Membership(member.getWorkspace().getPublicId(), member.getWorkspace().getName(),
                    member.getWorkspace().getKind(), member.getRole());
        }
    }

    public static UserProfileResponse from(User user, @Nullable UUID orgId,
            List<WorkspaceMember> memberships,
            boolean mfaEnabled, List<TermsVersionView> pendingConsents) {
        return new UserProfileResponse(user.getPublicId(), user.getEmail(), user.getName(),
                user.getRole(), orgId, user.getStatus(),
                memberships.stream().map(Membership::from).toList(), mfaEnabled, pendingConsents);
    }
}
