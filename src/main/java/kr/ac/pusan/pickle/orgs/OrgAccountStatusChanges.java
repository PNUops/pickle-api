package kr.ac.pusan.pickle.orgs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.AdminOrgOperationsQueryService;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrgAccountStatusChanges {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AdminOrgOperationsQueryService staffing;

    public OrgAccountStatusChanges(JdbcTemplate jdbc, AuditService audit, AdminOrgOperationsQueryService staffing) {
        this.jdbc = jdbc; this.audit = audit; this.staffing = staffing;
    }

    /** The withdrawal caller holds the administration barrier before checking and changing status. */
    public void requireMayWithdraw(long userId) {
        UUID account = jdbc.queryForObject("select public_id from users where id=?", UUID.class, userId);
        for (Long orgId : jdbc.queryForList("select org_id from user_org_roles where user_id=?", Long.class, userId)) {
            if (staffing.staffing(orgId, null).losesLastStaff(staffing.staffing(orgId, account))) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ORG_STAFF_VACANCY, "기관 업무를 먼저 이전해 주세요",
                        "탈퇴하면 마지막 활성 FULL 담당자 또는 신청 결정 담당자가 없어집니다. 기관 업무를 이전한 뒤 탈퇴해 주세요.");
            }
        }
    }

    /** Urgent suspension remains possible; each affected institution receives an explicit staffing record. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long userId, UUID userPublicId, UserStatus previous, UserStatus next,
            long actorId, String actorRole, String reason, String ip) {
        if (previous == next) { return; }
        for (var row : jdbc.queryForList("""
                select o.id, o.public_id, o.name from orgs o
                 join user_org_roles r on r.org_id=o.id where r.user_id=?
                """, userId)) {
            long orgId = ((Number) row.get("id")).longValue();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("userId", userPublicId); detail.put("beforeStatus", previous.name()); detail.put("afterStatus", next.name());
            detail.put("reason", reason == null ? "" : reason);
            boolean vacancy = false;
            try {
                var before = staffing.staffingWithStatus(orgId, userPublicId, previous);
                var after = staffing.staffingWithStatus(orgId, userPublicId, next);
                detail.put("staffingUnavailable", false);
                detail.put("activeAdminCount", after.fullCount()); detail.put("activeApproverCount", after.deciderCount());
                detail.put("decidersByKind", after.decidersByKind());
                detail.put("uncoveredKinds", after.decidersByKind().entrySet().stream()
                        .filter(entry -> entry.getValue() == 0 && before.decidersByKind().get(entry.getKey()) > 0)
                        .map(entry -> entry.getKey().name()).sorted().toList());
                vacancy = before.losesLastStaff(after);
            } catch (ApiException invalidAuthority) {
                if (!ErrorCodes.ORG_OPERATIONS_CONFLICT.equals(invalidAuthority.getCode())) { throw invalidAuthority; }
                // Account containment must not repair or depend on an inconsistent grant roster.
                detail.put("staffingUnavailable", true);
                detail.put("staffingError", invalidAuthority.getCode());
            }
            jdbc.update("update orgs set admin_revision=admin_revision+1 where id=?", orgId);
            audit.recordOrgChange(actorId, actorRole, vacancy ? "org.staff_vacancy" : "org.staff_status_update",
                    (UUID) row.get("public_id"), row.get("name").toString(), detail, ip);
        }
    }
}
