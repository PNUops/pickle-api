package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code OrgDetail}. */
public record OrgDetailResponse(
        Long id,
        String name,
        String slug,
        @Nullable String description,
        OrgStatus status,
        Instant createdAt) {

    public static OrgDetailResponse from(Org org) {
        return new OrgDetailResponse(org.getId(), org.getName(), org.getSlug(), org.getDescription(),
                org.getStatus(), org.getCreatedAt());
    }
}
