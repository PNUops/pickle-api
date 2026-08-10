package kr.ac.pusan.pickle.orgs.dto;

import kr.ac.pusan.pickle.orgs.Org;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.OrgStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code OrgSummaryResponse} (v0.15.0 adds {@code hidden}). */
public record OrgSummaryResponse(UUID id, String name, @Nullable String description,
        OrgStatus status, boolean hidden) {

    public static OrgSummaryResponse from(Org org) {
        return new OrgSummaryResponse(org.getPublicId(), org.getName(),
                org.getDescription(), org.getStatus(), org.isHidden());
    }
}
