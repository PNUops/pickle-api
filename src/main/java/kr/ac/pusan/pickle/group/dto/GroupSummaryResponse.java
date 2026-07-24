package kr.ac.pusan.pickle.group.dto;

import kr.ac.pusan.pickle.group.GroupKind;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code GroupSummary} (my-groups list). */
public record GroupSummaryResponse(
        Long id,
        GroupKind kind,
        String name,
        String slug,
        @Nullable String description,
        GroupMemberRole myRole,
        long memberCount) {

    public static GroupSummaryResponse from(GroupMember membership, long memberCount) {
        var group = membership.getGroup();
        return new GroupSummaryResponse(group.getId(), group.getKind(), group.getName(), group.getSlug(),
                group.getDescription(), membership.getRole(), memberCount);
    }
}
