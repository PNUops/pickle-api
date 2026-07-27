package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.user.UserStatus;

/**
 * Contract {@code AdminGroupMember} (v0.19.0): a group member in the admin
 * inspection view. Unlike the user-facing member list, every member is listed
 * regardless of account status — suspended/withdrawn members are exactly what
 * an admin audit needs to see — with {@code userStatus} carrying the state.
 */
public record AdminGroupMemberResponse(
        long userId,
        String name,
        String email,
        GroupMemberRole groupRole,
        UserStatus userStatus,
        Instant joinedAt) {
}
