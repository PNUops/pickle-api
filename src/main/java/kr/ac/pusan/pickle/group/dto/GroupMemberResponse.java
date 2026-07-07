package kr.ac.pusan.pickle.group.dto;

import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.user.User;

/** Contract schema {@code GroupMember}. */
public record GroupMemberResponse(Long userId, String name, String email, GroupMemberRole role) {

    public static GroupMemberResponse from(GroupMember member, User user) {
        return new GroupMemberResponse(member.getUserId(), user.getName(), user.getEmail(), member.getRole());
    }
}
