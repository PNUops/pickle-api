package kr.ac.pusan.pickle.orgs.dto;

import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgStatus;

/** Contract schema {@code OrgSummary}. */
public record OrgSummaryResponse(Long id, String name, String slug, String description,
        OrgStatus status) {

    public static OrgSummaryResponse from(Org org) {
        return new OrgSummaryResponse(org.getId(), org.getName(), org.getSlug(),
                org.getDescription(), org.getStatus());
    }
}
