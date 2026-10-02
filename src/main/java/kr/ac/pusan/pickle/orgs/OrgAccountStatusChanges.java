package kr.ac.pusan.pickle.orgs;

import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.user.UserStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrgAccountStatusChanges {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public OrgAccountStatusChanges(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public void requireMayWithdraw(long userId) {
        int vacancies = jdbc.queryForObject("""
                select count(*) from user_org_roles own where own.user_id = ?
                  and own.role in ('ORG_ADMIN', 'ORG_MANAGER') and not exists (
                    select 1 from user_org_roles other join users u on u.id = other.user_id
                     where other.org_id = own.org_id and other.user_id <> own.user_id
                       and u.status = 'ACTIVE'
                       and (other.role = 'ORG_ADMIN' or (own.role = 'ORG_MANAGER' and other.role = 'ORG_MANAGER')))
                """, Integer.class, userId);
        if (vacancies > 0) {
            throw new kr.ac.pusan.pickle.common.error.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    kr.ac.pusan.pickle.common.error.ErrorCodes.ORG_STAFF_VACANCY, "기관 역할을 먼저 이전해 주세요",
                    "탈퇴하면 활성 기관 관리자 또는 승인자가 없어집니다. 기관 역할을 이전한 뒤 탈퇴해 주세요.");
        }
    }

    /** A status change updates roster revisions; urgent suspension is never blocked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long userId, UUID userPublicId, UserStatus previous, UserStatus next,
            long actorId, String actorRole, String reason, String ip) {
        if (previous == next) return;
        var rows = jdbc.queryForList("""
                select o.id, o.public_id, o.name, r.role::text as role from orgs o
                 join user_org_roles r on r.org_id = o.id where r.user_id = ?
                """, userId);
        for (var row : rows) {
            long orgId = ((Number) row.get("id")).longValue();
            jdbc.update("update orgs set admin_revision = admin_revision + 1 where id = ?", orgId);
            int admins = jdbc.queryForObject("""
                    select count(*) from user_org_roles r join users u on u.id = r.user_id
                     where r.org_id = ? and r.role = 'ORG_ADMIN' and u.status = 'ACTIVE'
                    """, Integer.class, orgId);
            int approvers = jdbc.queryForObject("""
                    select count(*) from user_org_roles r join users u on u.id = r.user_id
                     where r.org_id = ? and r.role in ('ORG_ADMIN', 'ORG_MANAGER') and u.status = 'ACTIVE'
                    """, Integer.class, orgId);
            boolean vacancy = previous == UserStatus.ACTIVE && next != UserStatus.ACTIVE
                    && (("ORG_ADMIN".equals(row.get("role")) && admins == 0)
                        || ("ORG_MANAGER".equals(row.get("role")) && approvers == 0));
            audit.recordOrgChange(actorId, actorRole, vacancy ? "org.staff_vacancy" : "org.staff_status_update",
                    (UUID) row.get("public_id"), row.get("name").toString(),
                    Map.of("userId", userPublicId, "beforeStatus", previous.name(), "afterStatus", next.name(),
                            "activeAdminCount", admins, "activeApproverCount", approvers,
                            "reason", reason == null ? "" : reason), ip);
        }
    }
}
