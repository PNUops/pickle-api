package kr.ac.pusan.pickle.orgs.dto;

import kr.ac.pusan.pickle.orgs.Org;

/** Contract schema {@code OrgSummary}. */
public record OrgSummaryResponse(Long id, String name, String slug, String description) {

    public static OrgSummaryResponse from(Org org) {
        return new OrgSummaryResponse(org.getId(), org.getName(), org.getSlug(), org.getDescription());
    }
}
