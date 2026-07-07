package kr.ac.pusan.pickle.group.dto;

import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.group.GroupMemberRole;

/** Contract: PATCH /groups/{groupId}/members/{userId} body. */
public record UpdateGroupMemberRequest(
        @NotNull(message = "변경할 역할을 지정해 주세요.")
        GroupMemberRole role) {
}
