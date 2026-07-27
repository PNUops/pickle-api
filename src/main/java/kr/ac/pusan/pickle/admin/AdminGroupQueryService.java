package kr.ac.pusan.pickle.admin;

import java.util.List;
import kr.ac.pusan.pickle.admin.dto.AdminGroupDetailResponse;
import kr.ac.pusan.pickle.admin.dto.AdminGroupMemberResponse;
import kr.ac.pusan.pickle.admin.dto.AdminGroupOptionResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.group.GroupKind;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserStatus;
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
                select g.id, g.name, g.slug, g.kind, g.created_at,
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
                    AdminGroupQueryService::toOption, scopedOrgId, scopedOrgId);
        }
        // Sys tier without a filter: every live group (soft-deleted excluded)
        return jdbcTemplate.query(select + " where g.deleted_at is null order by g.id",
                AdminGroupQueryService::toOption);
    }

    private static AdminGroupOptionResponse toOption(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new AdminGroupOptionResponse(rs.getLong("id"), rs.getString("name"),
                rs.getString("slug"), rs.getLong("member_count"),
                GroupKind.valueOf(rs.getString("kind")),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * Contract {@code GET /admin/groups/{groupId}} (v0.19.0): admin inspection
     * detail. Unknown, soft-deleted, and (for the org tier) other-org groups
     * all answer the same 404 — the admin masking convention, unlike the
     * user-facing group detail's member-only 403.
     */
    @Transactional(readOnly = true)
    public AdminGroupDetailResponse get(AuthenticatedUser actor, long groupId) {
        Long scopedOrgId = scopeOrgId(actor, null);
        if (scopedOrgId != null) {
            Boolean linked = jdbcTemplate.queryForObject(
                    "select " + OrgMembershipSql.groupLinkedToOrg(String.valueOf(groupId)),
                    Boolean.class, scopedOrgId, scopedOrgId);
            if (!Boolean.TRUE.equals(linked)) {
                throw groupNotFound();
            }
        }
        List<AdminGroupDetailResponse> rows = jdbcTemplate.query("""
                select g.id, g.kind, g.name, g.slug, g.description, g.created_at,
                       (select count(*) from group_members gm
                          join users mu on mu.id = gm.user_id
                         where gm.group_id = g.id and mu.status = 'ACTIVE')
                           as member_count,
                       (select count(*) from vms v
                         where v.group_id = g.id and v.status <> 'DELETED') as vm_count
                  from groups g
                 where g.id = ? and g.deleted_at is null
                """, (rs, rowNum) -> new AdminGroupDetailResponse(rs.getLong("id"),
                        GroupKind.valueOf(rs.getString("kind")), rs.getString("name"),
                        rs.getString("slug"), rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("member_count"), rs.getLong("vm_count"), List.of()),
                groupId);
        if (rows.isEmpty()) {
            throw groupNotFound();
        }
        AdminGroupDetailResponse base = rows.getFirst();
        List<AdminGroupMemberResponse> members = jdbcTemplate.query("""
                select gm.user_id, u.name, u.email, gm.role, u.status, gm.created_at
                  from group_members gm
                  join users u on u.id = gm.user_id
                 where gm.group_id = ?
                 -- enum declaration order is OWNER first (V2), so asc = highest role first
                 order by gm.role asc, gm.user_id
                """, (rs, rowNum) -> new AdminGroupMemberResponse(rs.getLong("user_id"),
                        rs.getString("name"), rs.getString("email"),
                        GroupMemberRole.valueOf(rs.getString("role")),
                        UserStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant()),
                groupId);
        return new AdminGroupDetailResponse(base.id(), base.kind(), base.name(), base.slug(),
                base.description(), base.createdAt(), base.memberCount(), base.vmCount(), members);
    }

    private static ApiException groupNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 그룹이 존재하지 않습니다.");
    }

    /** Org tier pinned to their org; another org's id answers 404. */
    private static Long scopeOrgId(AuthenticatedUser actor, Long orgId) {
        if (!actor.role().isOrgTier()) {
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
