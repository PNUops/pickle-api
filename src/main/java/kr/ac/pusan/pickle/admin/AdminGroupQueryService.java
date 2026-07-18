package kr.ac.pusan.pickle.admin;

import java.util.List;
import kr.ac.pusan.pickle.admin.dto.AdminGroupOptionResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract {@code listAdminGroups}: the announcement group picker. ORG_ADMIN
 * sees exactly the groups they may target with a GROUP announcement — groups
 * linked to their org by the canonical derived-membership rule
 * ({@link OrgMembershipSql}: ≥1 vm_request or non-DELETED VM in the org).
 * {@code memberCount} counts the group's <b>ACTIVE</b> members — exactly the
 * fan-out basis of a GROUP announcement (contract clarification, docs
 * eb8bbf6). A cross-org {@code orgId} answers 404 (existence stays private);
 * the filter itself is SYS_ADMIN's.
 */
@Service
public class AdminGroupQueryService {

    private final JdbcTemplate jdbcTemplate;

    public AdminGroupQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<AdminGroupOptionResponse> list(AuthenticatedUser actor, Long orgId) {
        Long scopedOrgId = scopeOrgId(actor, orgId);
        String select = """
                select g.id, g.name, g.slug,
                       (select count(*) from group_members gm
                          join users mu on mu.id = gm.user_id
                         where gm.group_id = g.id and mu.status = 'ACTIVE')
                           as member_count
                  from groups g
                """;
        if (scopedOrgId != null) {
            return jdbcTemplate.query(
                    select + " where g.deleted_at is null and "
                            + OrgMembershipSql.groupLinkedToOrg("g.id") + " order by g.id",
                    (rs, rowNum) -> new AdminGroupOptionResponse(rs.getLong("id"),
                            rs.getString("name"), rs.getString("slug"),
                            rs.getLong("member_count")),
                    scopedOrgId, scopedOrgId);
        }
        // SYS_ADMIN without a filter: every live group (soft-deleted excluded)
        return jdbcTemplate.query(select + " where g.deleted_at is null order by g.id",
                (rs, rowNum) -> new AdminGroupOptionResponse(rs.getLong("id"),
                        rs.getString("name"), rs.getString("slug"), rs.getLong("member_count")));
    }

    /** ORG_ADMIN pinned to their org; another org's id answers 404. */
    private static Long scopeOrgId(AuthenticatedUser actor, Long orgId) {
        if (actor.role() != UserRole.ORG_ADMIN) {
            return orgId;
        }
        if (actor.orgId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        if (orgId != null && !orgId.equals(actor.orgId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
        }
        return actor.orgId();
    }
}
