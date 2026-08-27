package kr.ac.pusan.pickle.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.dto.ManagedOrgResponse;
import kr.ac.pusan.pickle.user.UserPosition;
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
        List<UserStatusChangeResponse> statusChanges,
        /*
         * 직책·학번·소속, readable here since 2026-08-27 because they became
         * write-once for the holder and an administrator now corrects them.
         * They are on the detail only, not the list: the list is a search
         * surface and 학번 is a personal identifier that six roles can reach.
         */
        @Nullable UserPosition position,
        @Nullable String studentNo,
        @Nullable String departmentCode,
        @Nullable String departmentName,
        @Nullable String departmentOther) {
}
