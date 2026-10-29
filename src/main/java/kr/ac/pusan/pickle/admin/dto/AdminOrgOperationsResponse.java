package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.orgs.RequestMailMode;
import org.jspecify.annotations.Nullable;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionPermissionView;

/** An organisation's actual role rows and its current request-mail interpretation. */
public record AdminOrgOperationsResponse(
        OrgDetailResponse org,
        long revision,
        @Nullable RequestMailMode mailMode,
        List<AdminOrgRoleMemberView> members,
        int activeAdminCount,
        int activeApproverCount,
        @Nullable Integer currentMailRecipientCount,
        @Nullable Boolean legacyFallback,
        @Nullable UUID requesterId,
        Instant observedAt,
        @Nullable RequestMailSelectionContext selectionContext,
        @Nullable List<RequestMailSelectionTarget> selectionTargets) {

    public AdminOrgOperationsResponse(OrgDetailResponse org,long revision,@Nullable RequestMailMode mailMode,
            List<AdminOrgRoleMemberView> members,int activeAdminCount,int activeApproverCount,
            @Nullable Integer currentMailRecipientCount,@Nullable Boolean legacyFallback,@Nullable UUID requesterId,Instant observedAt) {
        this(org,revision,mailMode,members,activeAdminCount,activeApproverCount,currentMailRecipientCount,legacyFallback,requesterId,observedAt,null,null);
    }

    public record RequestMailSelectionContext(kr.ac.pusan.pickle.access.ResourceType requestType, UUID workspaceId) { }
    public record RequestMailSelectionTarget(kr.ac.pusan.pickle.access.ResourceType requestType, UUID workspaceId, String workspaceName) { }

    @Schema(name = "AdminOrgRoleMemberView")
    public record AdminOrgRoleMemberView(
            UUID userId,
            String name,
            String email,
            @Schema(description = "이 기관에서 보유한 역할. 계정의 최고 역할과 구분합니다.") UserRole role,
            @Nullable InstitutionPermissionView permission,
            UserStatus status,
            @Nullable Boolean requestMail,
            @Nullable Boolean currentMailRecipient,
            @Nullable Boolean inAppRecipient,
            @Nullable String selectionReason,
            @Nullable String exclusionReason) {
    }
}
