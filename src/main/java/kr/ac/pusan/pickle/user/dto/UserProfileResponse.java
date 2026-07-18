package kr.ac.pusan.pickle.user.dto;

import java.util.List;
import kr.ac.pusan.pickle.group.GroupKind;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;

/** Contract schema {@code UserProfile}. */
public record UserProfileResponse(
        Long id,
        String email,
        String name,
        UserRole role,
        Long orgId,
        UserStatus status,
        List<Membership> memberships,
        boolean mfaEnabled,
        List<Object> pendingConsents) {

    public record Membership(Long groupId, String groupName, GroupKind groupKind, GroupMemberRole role) {

        public static Membership from(GroupMember member) {
            return new Membership(member.getGroup().getId(), member.getGroup().getName(),
                    member.getGroup().getKind(), member.getRole());
        }
    }

    public static UserProfileResponse from(User user, List<GroupMember> memberships,
            boolean mfaEnabled) {
        // pendingConsents stays empty until W2-A part 2 (terms/consent) lands.
        return new UserProfileResponse(user.getId(), user.getEmail(), user.getName(), user.getRole(),
                user.getOrgId(), user.getStatus(),
                memberships.stream().map(Membership::from).toList(), mfaEnabled, List.of());
    }
}
