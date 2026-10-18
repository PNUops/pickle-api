package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceDetailResponse;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceMemberResponse;
import kr.ac.pusan.pickle.admin.dto.AdminWorkspaceOptionResponse;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.orgs.OrgMembershipSql;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionResourceQueries;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.LifecycleActors;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationService;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.workspace.dto.WorkspaceInvitationResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace support projections and command references use the target's own WORKSPACE scope. */
@Service
public class AdminWorkspaceQueryService {
    private final JdbcTemplate jdbc;
    private final List<ResourceTypeAdapter> adapters;
    private final WorkspaceInvitationService invitations;
    private final InstitutionResourceQueries queries;
    private final ResourceInstitutionPermissions permissions;
    private final LifecycleActors actors;

    public AdminWorkspaceQueryService(List<ResourceTypeAdapter> adapters, JdbcTemplate jdbc,
            WorkspaceInvitationService invitations, InstitutionResourceQueries queries,
            ResourceInstitutionPermissions permissions, LifecycleActors actors) {
        this.jdbc = jdbc; this.adapters = adapters; this.invitations = invitations;
        this.queries = queries; this.permissions = permissions; this.actors = actors;
    }

    @Transactional(readOnly = true)
    public List<AdminWorkspaceOptionResponse> list(AuthenticatedUser actor, @Nullable UUID orgId, boolean all) {
        permissions.requireReadSource(actor, java.util.stream.Stream.concat(
                InstitutionResourceQueries.WORKSPACE_REFERENCE_WORKS.stream(), java.util.stream.Stream.concat(
                    InstitutionResourceQueries.REQUEST_WORKSPACE_REFERENCE_WORKS.stream(), java.util.stream.Stream.of(InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND)))
                .toArray(InstitutionWork[]::new));
        List<Long> ids = queries.workspaces(actor);
        Long requested = orgId == null ? null : jdbc.query("select id from orgs where public_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, orgId);
        if (orgId != null && requested == null) { return List.of(); }
        OrgScope filter = all ? OrgScope.unrestricted() : OrgScope.of(requested);
        List<Object> args = new ArrayList<>();
        String clause = "g.id in (" + idList(ids) + ")";
        if (!filter.isUnrestricted()) {
            clause += " and " + OrgMembershipSql.workspaceLinkedToOrg("g.id", filter);
            args.addAll(filter.orgIds()); args.addAll(filter.orgIds());
        }
        java.util.UUID commandInstitution = orgId;
        List<Long> commandReferences = new ArrayList<>(queries.requestWorkspaces(actor, commandInstitution));
        commandReferences.addAll(queries.announcementWorkspaces(actor, commandInstitution));
        if (!filter.isUnrestricted()) {
            // Full metadata rows retain the query filter; independently authorized command references remain minimal.
            commandReferences.removeAll(queries.workspaceMetadata(actor));
        }
        clause = "g.deleted_at is null and ((" + clause + ") or g.id in (" + idList(commandReferences) + "))";
        return jdbc.query("select g.id,g.public_id,g.name,g.kind,g.created_at,"
                + "(select count(*) from workspace_members m join users u on u.id=m.user_id "
                + "where m.workspace_id=g.id and u.status='ACTIVE') as members from workspaces g where "
                + clause + " order by g.id", (rs, row) -> {
                    UUID actual = rs.getObject("public_id", UUID.class);
                    boolean read = permissions.resolvedWorkspace(actor, InstitutionWork.WORKSPACE_SUPPORT_READ, actual,
                            UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN);
                    java.util.Set<String> work = InstitutionResourceQueries.WORKSPACE_REFERENCE_WORKS.stream()
                            .filter(item -> permissions.resolvedWorkspace(actor, item, actual,
                                    item.write() ? new UserRole[]{UserRole.SYS_ADMIN} : new UserRole[]{UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN}))
                            .map(InstitutionWork::id).collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
                    work.addAll(queries.workspaceCommandWorkIdsForResolvedWorkspace(actor, rs.getLong("id"), actual, commandInstitution));
                    return new AdminWorkspaceOptionResponse(actual, rs.getString("name"), read ? rs.getLong("members") : null,
                            WorkspaceKind.valueOf(rs.getString("kind")), read ? rs.getTimestamp("created_at").toInstant() : null, read, java.util.Set.copyOf(work));
                }, args.toArray());
    }

    private java.util.Set<String> allowedWorkIds(AuthenticatedUser actor, long workspace) { return allowedWorkIds(actor, workspace, null); }

    private java.util.Set<String> allowedWorkIds(AuthenticatedUser actor, long workspace, java.util.UUID selectedInstitution) {
        java.util.Set<String> result = InstitutionResourceQueries.WORKSPACE_REFERENCE_WORKS.stream()
                .filter(work -> may(actor, work, workspace)).map(InstitutionWork::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        result.addAll(queries.workspaceCommandWorkIds(actor, workspace, selectedInstitution));
        return java.util.Set.copyOf(result);
    }

    @Transactional(readOnly = true)
    public AdminWorkspaceDetailResponse get(AuthenticatedUser actor, UUID id) { return view(actor, id, null, false); }

    /** A successful command returns only its own current fields unless support read is independently held. */
    @Transactional(readOnly = true)
    public AdminWorkspaceDetailResponse commandReference(AuthenticatedUser actor, UUID id, InstitutionWork command, boolean descriptionChanged) {
        return view(actors.current(actor), id, command, descriptionChanged);
    }

    private AdminWorkspaceDetailResponse view(AuthenticatedUser actor, UUID id, @Nullable InstitutionWork command, boolean descriptionChanged) {
        long internal = requireLive(id);
        boolean read = readable(actor, internal);
        java.util.Set<String> allowed = allowedWorkIds(actor, internal);
        if (!read && allowed.isEmpty()) { throw notFound(); }
        boolean vmTotalReadable=read && queries.canReadWorkspaceVmTotal(actor,id);
        AdminWorkspaceDetailResponse base = jdbc.query("""
                select w.public_id,w.kind,w.name,w.description,w.created_at,
                    case when ? then (select count(*) from workspace_members m join users u on u.id=m.user_id
                        where m.workspace_id=w.id and u.status='ACTIVE') else null end as member_count,
                    case when ? then (select count(*) from vms v where v.workspace_id=w.id and v.status<>'DELETED') else null end as vm_count
                  from workspaces w where w.id=?
                """, (rs, row) -> new AdminWorkspaceDetailResponse(rs.getObject("public_id", UUID.class),
                        WorkspaceKind.valueOf(rs.getString("kind")), rs.getString("name"), rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant(), rs.getObject("member_count",Long.class), rs.getObject("vm_count",Long.class), List.of()), read,vmTotalReadable,internal).getFirst();
        List<AdminWorkspaceMemberResponse> members = null;
        boolean memberWork = may(actor, InstitutionWork.WORKSPACE_MEMBERS_MANAGE, internal);
        boolean ownerWork = may(actor, InstitutionWork.WORKSPACE_OWNERS_MANAGE, internal);
        boolean structure = base.kind() != WorkspaceKind.PERSONAL;
        boolean memberReference = memberWork || may(actor, InstitutionWork.WORKSPACE_OWNERS_MANAGE, internal)
                || may(actor, InstitutionWork.WORKSPACE_INVITATIONS_MANAGE, internal);
        if (read || memberReference) {
            long owners = jdbc.queryForObject("select count(*) from workspace_members where workspace_id=? and role='OWNER'", Long.class, internal);
            boolean profiles = permissions.global(actor, InstitutionWork.STUDENT_SUPPORT_READ,
                    UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN);
            members = jdbc.query("""
                    select u.public_id,u.name,u.email,u.role as account_role,u.status,m.role,m.created_at
                      from workspace_members m join users u on u.id=m.user_id where m.workspace_id=?
                     order by m.role asc,m.user_id
                    """, (rs, row) -> {
                        boolean owner = WorkspaceMemberRole.valueOf(rs.getString("role")) == WorkspaceMemberRole.OWNER;
                        boolean change = structure && ownerWork && (!owner || owners > 1);
                        boolean remove = structure && (owner ? ownerWork && owners > 1 : memberWork);
                        return new AdminWorkspaceMemberResponse(rs.getObject("public_id", UUID.class), rs.getString("name"),
                                read ? rs.getString("email") : null, WorkspaceMemberRole.valueOf(rs.getString("role")),
                                UserStatus.valueOf(rs.getString("status")), read && profiles && (actor.role().isSysTier() || !UserRole.valueOf(rs.getString("account_role")).isSysTier()), read ? rs.getTimestamp("created_at").toInstant() : null, change, remove);
                    }, internal);
        }
        boolean terminate = may(actor, InstitutionWork.WORKSPACE_TERMINATE, internal);
        boolean remaining = terminate && adapters.stream().anyMatch(adapter -> adapter.countLiveInWorkspace(internal) > 0);
        String blocked = !terminate ? "FORBIDDEN" : !structure ? "PERSONAL" : remaining ? "REMAINING_RESOURCES" : null;
        List<AdminWorkspaceDetailResponse.RosterCandidate> rosterCandidates = null;
        if (allowed.contains(InstitutionWork.VM_ROSTER_ISSUE.id()) || allowed.contains(InstitutionWork.LLM_ROSTER_ISSUE.id())) {
            rosterCandidates = jdbc.query("""
                    select u.public_id,u.name,m.role,u.status
                      from workspace_members m join users u on u.id=m.user_id
                     where m.workspace_id=? and u.status='ACTIVE' order by m.role,m.user_id
                    """, (rs, row) -> new AdminWorkspaceDetailResponse.RosterCandidate(rs.getObject("public_id", UUID.class),
                            rs.getString("name"), WorkspaceMemberRole.valueOf(rs.getString("role")),
                            UserStatus.valueOf(rs.getString("status"))), internal);
        }
        return new AdminWorkspaceDetailResponse(base.id(), base.kind(), base.name(), read || descriptionChanged ? base.description() : null, read ? base.createdAt() : null,
                read ? base.memberCount() : null, vmTotalReadable ? base.vmCount() : null, members, rosterCandidates,
                may(actor, InstitutionWork.WORKSPACE_INFO_UPDATE, internal),
                structure && may(actor, InstitutionWork.WORKSPACE_INVITATIONS_MANAGE, internal),
                structure && memberWork, structure && ownerWork, blocked == null, blocked, read, allowed);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceInvitationResponse> listInvitations(AuthenticatedUser actor, UUID id) {
        long internal = requireLive(id);
        if (!readable(actor, internal) && !may(actor, InstitutionWork.WORKSPACE_INVITATIONS_MANAGE, internal)) { throw notFound(); }
        return invitations.pending(internal);
    }

    public long requireOperated(AuthenticatedUser actor, UUID id) {
        long internal = requireLive(id);
        if (!readable(actor, internal)) { throw notFound(); }
        return internal;
    }

    private long requireLive(UUID id) {
        return jdbc.query("select id from workspaces where public_id=? and deleted_at is null",
                (rs, row) -> rs.getLong(1), id).stream().findFirst().orElseThrow(AdminWorkspaceQueryService::notFound);
    }

    private boolean readable(AuthenticatedUser actor, long id) {
        return permissions.workspace(actor, InstitutionWork.WORKSPACE_SUPPORT_READ, id,
                UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN);
    }

    private boolean may(AuthenticatedUser actor, InstitutionWork work, long id) {
        return permissions.workspace(actor, work, id, UserRole.SYS_ADMIN);
    }

    private static String idList(List<Long> ids) { return ids.isEmpty() ? "null" : String.join(",", ids.stream().map(String::valueOf).toList()); }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 워크스페이스가 존재하지 않습니다.");
    }
}
