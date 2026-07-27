package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.group.GroupKind;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code AdminGroupDetail} (v0.19.0): admin inspection view of one
 * group. {@code memberCount} keeps the option-list definition (ACTIVE members
 * = announcement fan-out basis) while {@code members} lists everyone; there is
 * deliberately no single org field — derived org membership is many-to-many
 * (a group belongs to every org it has requests/VMs in).
 */
public record AdminGroupDetailResponse(
        long id,
        GroupKind kind,
        String name,
        String slug,
        @Nullable String description,
        Instant createdAt,
        long memberCount,
        long vmCount,
        List<AdminGroupMemberResponse> members) {
}
