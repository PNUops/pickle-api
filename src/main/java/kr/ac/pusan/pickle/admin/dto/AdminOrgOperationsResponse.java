package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.orgs.RequestMailMode;
import org.jspecify.annotations.Nullable;

/** An organisation's actual role rows and its current request-mail interpretation. */
public record AdminOrgOperationsResponse(
        OrgDetailResponse org,
        long revision,
        @Nullable RequestMailMode mailMode,
        List<AdminOrgRoleMemberView> members,
        int activeAdminCount,
        int activeApproverCount,
        int currentMailRecipientCount,
        boolean legacyFallback,
        @Nullable UUID requesterId,
        Instant observedAt) {

    @Schema(name = "AdminOrgRoleMemberView")
    public record AdminOrgRoleMemberView(
            UUID userId,
            String name,
            String email,
            @Schema(description = "이 기관에서 보유한 역할. 계정의 최고 역할과 구분합니다.") UserRole role,
            UserStatus status,
            boolean requestMail,
            boolean currentMailRecipient,
            boolean inAppRecipient,
            @Nullable String selectionReason,
            @Nullable String exclusionReason) {
    }
}
