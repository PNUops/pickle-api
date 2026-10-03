package kr.ac.pusan.pickle.mail.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminOrgOperationsResponse.AdminOrgRoleMemberView;

public record RequestMailSelectionView(UUID requestId, UUID orgId, long policyRevision,
        String mailMode, Instant selectedAt, List<AdminOrgRoleMemberView> staff,
        RequestMailRequesterView requester) {
    public record RequestMailRequesterView(UUID userId, String email, String name, String status) {
    }
}
