package kr.ac.pusan.pickle.group.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupKind;
import kr.ac.pusan.pickle.group.GroupMemberRole;

/** Contract schema {@code GroupDetail} (group with members + my role in it). */
public record GroupDetailResponse(
        Long id,
        GroupKind kind,
        String name,
        String slug,
        String description,
        GroupMemberRole myRole,
        List<GroupMemberResponse> members,
        Instant createdAt) {

    public static GroupDetailResponse from(Group group, GroupMemberRole myRole,
            List<GroupMemberResponse> members) {
        return new GroupDetailResponse(group.getId(), group.getKind(), group.getName(), group.getSlug(),
                group.getDescription(), myRole, members, group.getCreatedAt());
    }
}
