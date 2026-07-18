package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.user.dto.UserProfileResponse;

/**
 * Contract schema {@code UserAdminDetail} (allOf {@code UserAdminView}) — the
 * base fields are flattened in so the JSON matches the contract's allOf.
 */
public record UserAdminDetailResponse(
        Long id,
        String email,
        String name,
        UserRole role,
        Long orgId,
        UserStatus status,
        boolean mfaEnabled,
        Instant createdAt,
        Instant withdrawnAt,
        Instant disabledAt,
        String disabledReason,
        List<UserProfileResponse.Membership> memberships,
        int activeVmCount,
        List<UserStatusChangeResponse> statusChanges) {
}
