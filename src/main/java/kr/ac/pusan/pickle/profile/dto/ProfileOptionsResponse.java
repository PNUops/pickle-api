package kr.ac.pusan.pickle.profile.dto;

import java.util.List;

/**
 * Contract: GET /meta/profile-options response body — the two catalogues the
 * signup form and the profile gate render. Public, like the terms endpoints,
 * because both are needed before an account exists.
 */
public record ProfileOptionsResponse(List<PositionView> positions, List<DepartmentView> departments) {
}
