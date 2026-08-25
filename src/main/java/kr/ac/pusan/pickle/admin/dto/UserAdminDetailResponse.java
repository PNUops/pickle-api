package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.dto.ManagedOrgResponse;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.user.dto.UserProfileResponse;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code UserAdminDetail} (allOf {@code UserAdminView}) — the
 * base fields are flattened in so the JSON matches the contract's allOf.
 */
public record UserAdminDetailResponse(
        UUID id,
        String email,
        String name,
        UserRole role,
        List<ManagedOrgResponse> managedOrgs,
        UserStatus status,
        boolean mfaEnabled,
        Instant createdAt,
        @Nullable Instant withdrawnAt,
        @Nullable Instant disabledAt,
        @Nullable String disabledReason,
        List<UserProfileResponse.Membership> memberships,
        int activeVmCount,
        List<UserStatusChangeResponse> statusChanges) {
}
