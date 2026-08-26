package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.dto.ManagedOrgResponse;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;

/** Contract schema {@code UserAdminView} — admin user-list item. */
public record UserAdminViewResponse(
        UUID id,
        String email,
        String name,
        UserRole role,
        List<ManagedOrgResponse> managedOrgs,
        UserStatus status,
        boolean mfaEnabled,
        Instant createdAt) {
}
