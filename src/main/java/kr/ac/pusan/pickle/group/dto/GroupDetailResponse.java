package kr.ac.pusan.pickle.group.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.group.Group;
import kr.ac.pusan.pickle.group.GroupKind;

/** Contract schema {@code GroupDetail} (group with members). */
public record GroupDetailResponse(
        Long id,
        GroupKind kind,
        String name,
        String slug,
        String description,
        List<GroupMemberResponse> members,
        Instant createdAt) {

    public static GroupDetailResponse from(Group group, List<GroupMemberResponse> members) {
        return new GroupDetailResponse(group.getId(), group.getKind(), group.getName(), group.getSlug(),
                group.getDescription(), members, group.getCreatedAt());
    }
}
