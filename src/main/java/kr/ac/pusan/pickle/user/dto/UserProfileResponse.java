package kr.ac.pusan.pickle.user.dto;

import java.util.List;
import kr.ac.pusan.pickle.consent.dto.TermsVersionView;
import kr.ac.pusan.pickle.group.GroupKind;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code UserProfile}. */
public record UserProfileResponse(
        Long id,
        String email,
        String name,
        UserRole role,
        @Nullable Long orgId,
        UserStatus status,
        List<Membership> memberships,
        boolean mfaEnabled,
        List<TermsVersionView> pendingConsents) {

    public record Membership(Long groupId, String groupName, GroupKind groupKind, GroupMemberRole role) {

        public static Membership from(GroupMember member) {
            return new Membership(member.getGroup().getId(), member.getGroup().getName(),
                    member.getGroup().getKind(), member.getRole());
        }
    }

    public static UserProfileResponse from(User user, List<GroupMember> memberships,
            boolean mfaEnabled, List<TermsVersionView> pendingConsents) {
        return new UserProfileResponse(user.getId(), user.getEmail(), user.getName(), user.getRole(),
                user.getOrgId(), user.getStatus(),
                memberships.stream().map(Membership::from).toList(), mfaEnabled, pendingConsents);
    }
}
