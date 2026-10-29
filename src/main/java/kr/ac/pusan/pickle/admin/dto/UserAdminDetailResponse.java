package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.dto.ManagedOrgResponse;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
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
        @Nullable List<ManagedOrgResponse> managedOrgs,
        UserStatus status,
        @Nullable Boolean mfaEnabled,
        @Nullable Instant createdAt,
        @Nullable Instant withdrawnAt,
        @Nullable Instant disabledAt,
        @Nullable String disabledReason,
        @Nullable List<AdminMembershipResponse> memberships,
        @Nullable Integer activeVmCount,
        @Nullable List<UserStatusChangeResponse> statusChanges,
        // Profile fields are visible to every support reader for the displayed account.
        @Nullable UserPosition position,
        @Nullable String studentNo,
        @Nullable String departmentCode,
        @Nullable String departmentName,
        @Nullable String departmentOther,
        AdminUserSupportCapabilitiesResponse supportCapabilities,
        boolean relationshipsLimited) {
}
