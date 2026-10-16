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
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Global support reads over live workspaces. The optional institution filters
 * derived links through requests and resources. Announcement sending still
 * uses its own institution authority and ACTIVE-member fan-out rules.
 */
@Service
public class AdminWorkspaceQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final java.util.List<kr.ac.pusan.pickle.resource.ResourceTypeAdapter> adapters;
    private final kr.ac.pusan.pickle.workspace.WorkspaceInvitationService invitationService;

    public AdminWorkspaceQueryService(java.util.List<kr.ac.pusan.pickle.resource.ResourceTypeAdapter> adapters, JdbcTemplate jdbcTemplate,
            kr.ac.pusan.pickle.workspace.WorkspaceInvitationService invitationService) {
        this.jdbcTemplate = jdbcTemplate; this.adapters = adapters;
        this.invitationService = invitationService;
    }

    /**
     * Every live workspace is searchable by support readers. The optional institution
     * narrows derived membership; the legacy {@code all=true} explicitly bypasses
     * that filter for clients using the unrestricted picker.
     */
    @Transactional(readOnly = true)
    public List<AdminWorkspaceOptionResponse> list(AuthenticatedUser actor, UUID orgId, boolean all) {
        StudentSupportPolicy.requireRead(actor);
        OrgScope scope = all ? OrgScope.unrestricted() : scopeOrgId(actor, orgId);
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
     * detail. Unknown and soft-deleted workspaces answer 404, the admin masking
     * convention, unlike the user-facing workspace detail's member-only 403.
     * Every admin role reads every workspace (operator decision, 2026-09-28;
     * see {@link #list}).
     */
    @Transactional(readOnly = true)
    public AdminWorkspaceDetailResponse get(AuthenticatedUser actor, UUID publicWorkspaceId) {
        StudentSupportPolicy.requireRead(actor);
        Long workspaceId = jdbcTemplate.query("select id from workspaces where public_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, publicWorkspaceId);
        if (workspaceId == null) {
            throw workspaceNotFound();
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
                       u.role as user_role,
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
                        StudentSupportPolicy.canReadUser(actor, UserRole.valueOf(rs.getString("user_role"))),
                        rs.getTimestamp("created_at").toInstant()),
                workspaceId);
        boolean writer = StudentSupportPolicy.canWriteWorkspaceAccess(actor);
        boolean structure = writer && base.kind() != WorkspaceKind.PERSONAL;
        long owners = members.stream().filter(m -> m.workspaceRole() == WorkspaceMemberRole.OWNER).count();
        List<AdminWorkspaceMemberResponse> capableMembers = members.stream().map(m -> new AdminWorkspaceMemberResponse(
                m.userId(), m.name(), m.email(), m.workspaceRole(), m.userStatus(), m.supportDetailVisible(), m.joinedAt(),
                structure && (m.workspaceRole() != WorkspaceMemberRole.OWNER || owners > 1),
                structure && (m.workspaceRole() != WorkspaceMemberRole.OWNER || owners > 1))).toList();
        boolean remaining = adapters.stream().anyMatch(a -> a.countLiveInWorkspace(workspaceId) > 0);
        String blocked = !writer ? "FORBIDDEN" : base.kind() == WorkspaceKind.PERSONAL ? "PERSONAL"
                : remaining ? "REMAINING_RESOURCES" : null;
        return new AdminWorkspaceDetailResponse(base.id(), base.kind(), base.name(),
                base.description(), base.createdAt(), base.memberCount(), base.vmCount(), capableMembers,
                writer, structure, structure, structure, blocked == null, blocked);
    }

    /** Pending invitation metadata for all support readers; deleted workspaces remain unavailable. */
    @Transactional(readOnly = true)
    public List<kr.ac.pusan.pickle.workspace.dto.WorkspaceInvitationResponse> listInvitations(
            AuthenticatedUser actor, UUID publicWorkspaceId) {
        return invitationService.pending(requireOperated(actor, publicWorkspaceId));
    }

    /**
     * The internal id of a live workspace, for an admin role acting on it as
     * an approver. Any live workspace, PERSONAL included, for every admin role
     * (operator decision, 2026-09-28; see {@link #list}). Unknown and deleted
     * workspaces answer 404.
     */
    @Transactional(readOnly = true)
    public long requireOperated(AuthenticatedUser actor, UUID publicWorkspaceId) {
        Long workspaceId = jdbcTemplate.query(
                "select id from workspaces where public_id = ? and deleted_at is null",
                rs -> rs.next() ? rs.getLong(1) : null, publicWorkspaceId);
        if (workspaceId == null) {
            throw workspaceNotFound();
        }
        return workspaceId;
    }

    private static ApiException workspaceNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 워크스페이스가 존재하지 않습니다.");
    }

    private OrgScope scopeOrgId(AuthenticatedUser actor, UUID orgId) {
        Long requested = orgId == null ? null : jdbcTemplate.query(
                "select id from orgs where public_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, orgId);
        return StudentSupportPolicy.filterScope(actor, orgId, requested);
    }
}
