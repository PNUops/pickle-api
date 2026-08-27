package kr.ac.pusan.pickle.user.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.consent.dto.TermsVersionView;
import kr.ac.pusan.pickle.identity.IdentityProvider;
import kr.ac.pusan.pickle.identity.UserIdentity;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.orgs.dto.ManagedOrgResponse;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code UserProfile}. */
public record UserProfileResponse(
        UUID id,
        String email,
        String name,
        UserRole role,
        List<ManagedOrgResponse> managedOrgs,
        UserStatus status,
        List<Membership> memberships,
        boolean mfaEnabled,
        List<TermsVersionView> pendingConsents,
        @Nullable UserPosition position,
        @Nullable String studentNo,
        @Nullable String departmentCode,
        @Nullable String departmentName,
        @Nullable String departmentOther,
        /**
         * Whether 직책·소속 (and 학번 where the position needs one) are filled
         * in. The console gates on this rather than on the fields being null:
         * which fields are required depends on the position, so re-deriving it
         * on the far side is a second copy of a rule that already exists here.
         */
        boolean profileComplete,
        /**
         * False for an account created through an external identity. The
         * account settings screen needs it to offer "비밀번호 설정" instead of
         * "비밀번호 변경", which asks for a current password that does not exist.
         */
        boolean hasPassword,
        List<LinkedIdentity> identities) {

    public record Membership(UUID workspaceId, String workspaceName, WorkspaceKind workspaceKind, WorkspaceMemberRole role) {

        public static Membership from(WorkspaceMember member) {
            return new Membership(member.getWorkspace().getPublicId(), member.getWorkspace().getName(),
                    member.getWorkspace().getKind(), member.getRole());
        }
    }

    /**
     * One external login linked to this account. The address is the one the
     * link was made with and is not kept in step with {@code email} — it is
     * here so the holder can recognise which account was linked, not as a
     * second source of truth for who they are.
     */
    public record LinkedIdentity(IdentityProvider provider, String email, Instant linkedAt) {

        public static LinkedIdentity from(UserIdentity identity) {
            return new LinkedIdentity(identity.getProvider(), identity.getEmailAtLink(),
                    identity.getLinkedAt());
        }
    }

    public static UserProfileResponse from(User user, List<ManagedOrgResponse> managedOrgs,
            List<WorkspaceMember> memberships,
            boolean mfaEnabled, List<TermsVersionView> pendingConsents,
            @Nullable String departmentName, List<UserIdentity> identities) {
        return new UserProfileResponse(user.getPublicId(), user.getEmail(), user.getName(),
                user.getRole(), managedOrgs, user.getStatus(),
                memberships.stream().map(Membership::from).toList(), mfaEnabled, pendingConsents,
                user.getPosition(), user.getStudentNo(), user.getDepartmentCode(), departmentName,
                user.getDepartmentOther(), user.isProfileComplete(), user.hasPassword(),
                identities.stream().map(LinkedIdentity::from).toList());
    }
}
