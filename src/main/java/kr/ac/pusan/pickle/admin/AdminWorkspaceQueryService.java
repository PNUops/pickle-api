package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceDetailResponse;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceMemberResponse;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceOptionResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract {@code listAdminWorkspaces}: the announcement workspace picker. ORG_ADMIN
 * sees exactly the workspaces they may target with a WORKSPACE announcement — workspaces
 * linked to their org by the canonical derived-membership rule
 * ({@link OrgMembershipSql}: ≥1 request or non-DELETED VM in the org).
 * {@code memberCount} counts the workspace's <b>ACTIVE</b> members — exactly the
 * fan-out basis of a WORKSPACE announcement (contract clarification, docs
 * eb8bbf6). A cross-org {@code orgId} answers 404 (existence stays private);
 * the filter itself is SYS_ADMIN's.
 */
@Service
public class AdminWorkspaceQueryService {
    /**
     * The scope an id no org has resolves to: a filter value no row carries, so
     * the page comes back empty exactly as a non-matching number made it.
     */

    private final JdbcTemplate jdbcTemplate;

    public AdminWorkspaceQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<AdminWorkspaceOptionResponse> list(AuthenticatedUser actor, UUID orgId) {
        OrgScope scope = scopeOrgId(actor, orgId);
        String select = """
                select g.public_id, g.name, g.kind, g.created_at,
                       (select count(*) from workspace_members gm
                          join users mu on mu.id = gm.user_id
                         where gm.workspace_id = g.id and mu.status = 'ACTIVE')
                           as member_count
                  from workspaces g
                """;
        if (!scope.isUnrestricted()) {
            List<Object> params = new ArrayList<>(scope.orgIds());
            params.addAll(scope.orgIds());
            return jdbcTemplate.query(
                    select + " where g.deleted_at is null and "
                            + OrgMembershipSql.workspaceLinkedToOrg("g.id", scope)
                            + " order by g.id",
                    AdminWorkspaceQueryService::toOption, params.toArray());
        }
        // Sys tier without a filter: every live workspace (soft-deleted excluded)
        return jdbcTemplate.query(select + " where g.deleted_at is null order by g.id",
                AdminWorkspaceQueryService::toOption);
    }

    private static AdminWorkspaceOptionResponse toOption(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new AdminWorkspaceOptionResponse(rs.getObject("public_id", UUID.class),
                rs.getString("name"),
                rs.getLong("member_count"),
                WorkspaceKind.valueOf(rs.getString("kind")),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * Contract {@code GET /admin/workspaces/{workspaceId}} (v0.19.0): admin inspection
     * detail. Unknown, soft-deleted, and (for the org tier) other-org workspaces
     * all answer the same 404 — the admin masking convention, unlike the
     * user-facing workspace detail's member-only 403.
     */
    @Transactional(readOnly = true)
    public AdminWorkspaceDetailResponse get(AuthenticatedUser actor, UUID publicWorkspaceId) {
        Long workspaceId = jdbcTemplate.query("select id from workspaces where public_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, publicWorkspaceId);
        if (workspaceId == null) {
            throw workspaceNotFound();
        }
        OrgScope scope = scopeOrgId(actor, null);
        if (!scope.isUnrestricted()) {
            List<Object> params = new ArrayList<>(scope.orgIds());
            params.addAll(scope.orgIds());
            Boolean linked = jdbcTemplate.queryForObject(
                    "select " + OrgMembershipSql.workspaceLinkedToOrg(
                            String.valueOf(workspaceId), scope),
                    Boolean.class, params.toArray());
            if (!Boolean.TRUE.equals(linked)) {
                throw workspaceNotFound();
            }
        }
        List<AdminWorkspaceDetailResponse> rows = jdbcTemplate.query("""
                select g.public_id, g.kind, g.name, g.description, g.created_at,
                       (select count(*) from workspace_members gm
                          join users mu on mu.id = gm.user_id
                         where gm.workspace_id = g.id and mu.status = 'ACTIVE')
                           as member_count,
                       (select count(*) from vms v
                         where v.workspace_id = g.id and v.status <> 'DELETED') as vm_count
                  from workspaces g
                 where g.id = ? and g.deleted_at is null
                """, (rs, rowNum) -> new AdminWorkspaceDetailResponse(
                        rs.getObject("public_id", UUID.class),
                        WorkspaceKind.valueOf(rs.getString("kind")), rs.getString("name"),
                        rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("member_count"), rs.getLong("vm_count"), List.of()),
                workspaceId);
        if (rows.isEmpty()) {
            throw workspaceNotFound();
        }
        AdminWorkspaceDetailResponse base = rows.getFirst();
        List<AdminWorkspaceMemberResponse> members = jdbcTemplate.query("""
                select u.public_id as user_public_id, u.name, u.email, gm.role, u.status,
                       gm.created_at
                  from workspace_members gm
                  join users u on u.id = gm.user_id
                 where gm.workspace_id = ?
                 -- enum declaration order is OWNER first (V2), so asc = highest role first
                 order by gm.role asc, gm.user_id
                """, (rs, rowNum) -> new AdminWorkspaceMemberResponse(
                        rs.getObject("user_public_id", UUID.class),
                        rs.getString("name"), rs.getString("email"),
                        WorkspaceMemberRole.valueOf(rs.getString("role")),
                        UserStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant()),
                workspaceId);
        return new AdminWorkspaceDetailResponse(base.id(), base.kind(), base.name(),
                base.description(), base.createdAt(), base.memberCount(), base.vmCount(), members);
    }

    private static ApiException workspaceNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 워크스페이스가 존재하지 않습니다.");
    }

    /** Org tier pinned to their org; another org's id answers 404. */
    private OrgScope scopeOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null : jdbcTemplate.query(
                "select id from orgs where public_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, orgId);
        if (!actor.role().isOrgTier()) {
            // An id no org has filters to nothing, as a non-matching number did.
            if (orgId != null && requested == null) {
                return OrgScope.nothing();
            }
            return OrgScope.of(requested);
        }
        if (actor.managedOrgIds().isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "관리 기관이 지정되지 않은 계정입니다.");
        }
        if (orgId != null && !actor.manages(requested)) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                    "리소스를 찾을 수 없습니다", "해당 기관을 찾을 수 없습니다.");
        }
        return orgId != null ? OrgScope.of(requested) : OrgScope.of(actor.managedOrgIds());
    }
}
