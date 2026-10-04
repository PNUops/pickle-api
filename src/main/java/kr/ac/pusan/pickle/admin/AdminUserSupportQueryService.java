package kr.ac.pusan.pickle.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.AccessGranteeType;
import kr.ac.pusan.pickle.access.ResourceAccessGrant;
import kr.ac.pusan.pickle.access.ResourceAccessGrantRepository;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminUserResourceAccessResponse;
import kr.ac.pusan.pickle.admin.dto.AdminUserSupportResponse;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.orgs.AdminOrgScope;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A directory relationship never widens the institution scope of a resource or request. */
@Service
public class AdminUserSupportQueryService {
    private static final String RESOURCE_ROWS = """
            select 'VM' as type, id, public_id, name, status::text, workspace_id, org_id,
                   request_id, end_date, null::timestamptz as expires_at, null::timestamptz as released_at
              from vms
            union all
            select 'LLM_API_KEY', id, public_id, name,
                   case when status in ('PENDING','ACTIVE','SUSPENDED') and expires_at <= ?
                        then 'EXPIRED' else status::text end,
                   workspace_id, org_id, request_id, null::date, expires_at, null::timestamptz
              from llm_api_keys
            union all
            select 'DOMAIN', id, public_id, fqdn, status::text, workspace_id, org_id,
                   null::bigint, null::date, renew_due_at, released_at
              from domains where kind = 'EXTERNAL'
            union all
            select 'GPU', id, public_id, name, status::text, workspace_id, org_id,
                   request_id, granted_end_date, lease_ends_at, null::timestamptz
              from gpu_allocations
            """;

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ResourceAccessResolver resolver;
    private final ResourceAccessGrantRepository grants;
    private final Clock clock;

    public AdminUserSupportQueryService(JdbcTemplate jdbc, UserRepository users,
            ResourceAccessResolver resolver, ResourceAccessGrantRepository grants, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.resolver = resolver;
        this.grants = grants;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminUserSupportResponse support(AuthenticatedUser actor, UUID userId, @Nullable UUID orgId) {
        User user = requireUser(actor, userId);
        OrgScope scope = scope(actor, orgId);
        Instant now = clock.instant();
        List<ResourceRow> rows = resources(scope, now, "(exists (select 1 from workspace_members m "
                + "where m.workspace_id = x.workspace_id and m.user_id = ?) or exists "
                + "(select 1 from resource_access_grants g where g.resource_type::text = x.type "
                + "and g.resource_id = x.id and g.grantee_type = 'USER' and g.user_id = ?))", user.getId(), user.getId());
        List<AdminUserResourceAccessResponse> resources = rows.stream()
                .map(row -> access(actor, user, row, now)).toList();
        List<AdminUserSupportResponse.AdminUserSupportMembership> memberships = jdbc.query("""
                select w.id, w.public_id, w.name, w.kind, m.role
                  from workspace_members m join workspaces w on w.id = m.workspace_id
                 where m.user_id = ? and w.deleted_at is null order by w.id
                """, (rs, row) -> {
                    UUID workspaceId = rs.getObject("public_id", UUID.class);
                    long workspaceInternalId = rs.getLong("id");
                    List<AdminUserSupportResponse.AdminUserSupportResourceCount> counts = java.util.Arrays.stream(ResourceType.values())
                            .map(type -> new AdminUserSupportResponse.AdminUserSupportResourceCount(type,
                                    type == ResourceType.DOMAIN ? domainCount(scope, workspaceInternalId)
                                    : resources.stream().filter(resource -> resource.workspaceId().equals(workspaceId)
                                            && resource.type() == type).count())).toList();
                    return new AdminUserSupportResponse.AdminUserSupportMembership(workspaceId, rs.getString("name"),
                            WorkspaceKind.valueOf(rs.getString("kind")),
                            WorkspaceMemberRole.valueOf(rs.getString("role")), counts);
                }, user.getId());
        boolean invitationsVisible = approver(actor);
        return new AdminUserSupportResponse(userId, now, memberships, invitationsVisible,
                invitationsVisible ? invitations(actor, user) : List.of(), requests(actor, user, scope), resources);
    }

    private long domainCount(OrgScope scope, long workspaceId) {
        Long count = jdbc.queryForObject("select count(*) from domains d where d.workspace_id = ? and "
                + scope.guard("d.org_id"), Long.class, workspaceId, scope.arrayParam(), scope.arrayParam());
        return count == null ? 0 : count;
    }

    @Transactional(readOnly = true)
    public AdminUserResourceAccessResponse diagnose(AuthenticatedUser actor, UUID userId,
            @Nullable UUID orgId, ResourceType type, UUID resourceId) {
        User user = requireUser(actor, userId);
        Instant now = clock.instant();
        List<ResourceRow> rows = resources(scope(actor, orgId), now,
                "x.type = ? and x.public_id = ?", type.name(), resourceId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return access(actor, user, rows.getFirst(), now);
    }

    User requireUser(AuthenticatedUser actor, UUID userId) {
        return users.findByPublicId(userId)
                .filter(user -> !actor.role().isOrgTier() || !user.getRole().isSysTier())
                .orElseThrow(AdminUserSupportQueryService::notFound);
    }

    private OrgScope scope(AuthenticatedUser actor, @Nullable UUID orgId) {
        Long internal = orgId == null ? null : jdbc.query("select id from orgs where public_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, orgId);
        return AdminOrgScope.read(actor, orgId, internal);
    }

    private List<ResourceRow> resources(OrgScope scope, Instant now, String predicate, Object... args) {
        List<Object> params = new ArrayList<>();
        params.add(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        params.add(scope.arrayParam());
        params.add(scope.arrayParam());
        params.addAll(java.util.Arrays.asList(args));
        return jdbc.query("select x.*, w.public_id as workspace_public_id, w.name as workspace_name, "
                + "o.public_id as org_public_id, o.name as org_name, r.public_id as request_public_id "
                + "from (" + RESOURCE_ROWS + ") x join workspaces w on w.id = x.workspace_id "
                + "join orgs o on o.id = x.org_id left join requests r on r.id = x.request_id "
                + "where " + scope.guard("x.org_id") + " and " + predicate
                + " order by x.type, x.id desc", AdminUserSupportQueryService::resourceRow, params.toArray());
    }

    private AdminUserResourceAccessResponse access(AuthenticatedUser actor, User user, ResourceRow resource,
            Instant now) {
        ResourceStanding standing = resolver.standing(resource.type(), resource.id(), resource.workspaceId(), user.getId());
        WorkspaceMemberRole membership = jdbc.query("""
                select role from workspace_members where workspace_id = ? and user_id = ?
                """, rs -> rs.next() ? WorkspaceMemberRole.valueOf(rs.getString(1)) : null,
                resource.workspaceId(), user.getId());
        List<ResourceAccessGrant> entries = grants.findByResourceTypeAndResourceIdOrderByIdAsc(resource.type(), resource.id());
        ResourceRole personal = entries.stream().filter(grant -> Long.valueOf(user.getId()).equals(grant.getUserId()))
                .map(ResourceAccessGrant::getRole).findFirst().orElse(null);
        ResourceRole workspace = entries.stream().filter(grant -> grant.getGranteeType() == AccessGranteeType.WORKSPACE)
                .map(ResourceAccessGrant::getRole).findFirst().orElse(null);
        boolean active = user.getStatus() == UserStatus.ACTIVE;
        List<String> reasons = new ArrayList<>();
        if (!active) { reasons.add("ACCOUNT_INACTIVE"); }
        if (!standing.owningWorkspaceMember()) { reasons.add("NOT_WORKSPACE_MEMBER"); }
        if (standing.role() == null) { reasons.add("NO_RESOURCE_GRANT"); }
        boolean ended = switch (resource.type()) {
            case VM -> List.of("DELETED", "DELETING").contains(resource.status())
                    || resource.endDate() != null && resource.endDate().isBefore(ClockConfig.todayKst(clock));
            case LLM_API_KEY -> List.of("EXPIRED", "REVOKED").contains(resource.status());
            case DOMAIN -> "REMOVED".equals(resource.status()) || resource.releasedAt() != null
                    || resource.expiresAt() != null && !resource.expiresAt().isAfter(now);
            case GPU -> List.of("RELEASED", "CANCELED").contains(resource.status())
                    || resource.endDate() != null && resource.endDate().isBefore(ClockConfig.todayKst(clock))
                    || resource.expiresAt() != null && !resource.expiresAt().isAfter(now);
        };
        if (ended) { reasons.add("RESOURCE_ENDED"); }
        boolean ready = switch (resource.type()) {
            case VM -> "RUNNING".equals(resource.status());
            case LLM_API_KEY, DOMAIN -> "ACTIVE".equals(resource.status());
            case GPU -> "ALLOCATED".equals(resource.status());
        };
        if (!ended && !ready) { reasons.add("RESOURCE_NOT_READY"); }
        String revokeReason = actor.role() != UserRole.SYS_ADMIN && !actor.administers(resource.orgId())
                ? "FORBIDDEN" : resource.type() == ResourceType.DOMAIN && "REMOVED".equals(resource.status())
                ? "RESOURCE_NOT_FOUND" : personal == null ? "NO_PERSONAL_GRANT" : "ALLOWED";
        return new AdminUserResourceAccessResponse(resource.type(), resource.publicId(), resource.name(), resource.status(),
                resource.orgPublicId(), resource.orgName(), resource.workspacePublicId(), resource.workspaceName(),
                resource.requestPublicId(), user.getStatus(), membership, personal, workspace, standing.role(),
                standing.standingRights(), active && standing.owningWorkspaceMember() && standing.role() != null,
                "ALLOWED".equals(revokeReason), revokeReason, List.copyOf(reasons));
    }

    private List<AdminUserSupportResponse.AdminUserSupportInvitation> invitations(AuthenticatedUser actor, User user) {
        String studentNo = actor.role() == UserRole.SYS_ADMIN ? user.getStudentNo() : null;
        return jdbc.query("""
                select i.public_id, w.public_id as workspace_public_id, w.name, w.kind, i.role,
                       i.status, i.accepted_at,
                       case when i.accepted_user_id = ? then 'ACCEPTED'
                            when lower(i.invitee_email) = lower(?) then 'EMAIL' else 'STUDENT_NO' end as matched_by
                  from workspace_invitations i join workspaces w on w.id = i.workspace_id
                 where w.deleted_at is null and (i.accepted_user_id = ? or
                       (i.status = 'PENDING' and (lower(i.invitee_email) = lower(?) or
                        (cast(? as text) is not null and upper(i.invitee_student_no) = upper(cast(? as text))))))
                 order by i.id desc
                """, (rs, row) -> new AdminUserSupportResponse.AdminUserSupportInvitation(rs.getObject("public_id", UUID.class),
                        rs.getObject("workspace_public_id", UUID.class), rs.getString("name"),
                        WorkspaceKind.valueOf(rs.getString("kind")), WorkspaceMemberRole.valueOf(rs.getString("role")),
                        WorkspaceInvitationStatus.valueOf(rs.getString("status")), rs.getString("matched_by"),
                        instant(rs, "accepted_at")), user.getId(), user.getEmail(), user.getId(), user.getEmail(),
                studentNo, studentNo);
    }

    private List<AdminUserSupportResponse.AdminUserSupportRelatedRequest> requests(AuthenticatedUser actor, User user, OrgScope scope) {
        String studentNo = actor.role() == UserRole.SYS_ADMIN ? user.getStudentNo() : null;
        OrgScope pendingScope = actor.role() == UserRole.SYS_ADMIN ? OrgScope.unrestricted()
                : actor.role().isOrgTier() ? OrgScope.of(actor.operatedOrgIds()) : OrgScope.nothing();
        return jdbc.query("""
                select r.public_id, r.resource_type, r.status, w.public_id as workspace_public_id,
                       w.name as workspace_name, o.public_id as org_public_id, o.name as org_name,
                       r.requester_id = ? as applicant, rr.public_id as recipient_public_id,
                       rr.status as recipient_status, rr.reason, rv.granted_end_date,
                       coalesce(v.public_id, k.public_id, gpu.public_id) as resource_public_id
                  from requests r join workspaces w on w.id = r.workspace_id join orgs o on o.id = r.org_id
                  left join request_recipients rr on rr.request_id = r.id and
                       (rr.user_id = ? or exists (select 1 from workspace_invitations accepted
                            where accepted.id = rr.invitation_id and accepted.accepted_user_id = ?)
                        or (""" + pendingScope.guard("r.org_id") + """
                        and exists (select 1 from workspace_invitations i
                          where i.id = rr.invitation_id and i.status = 'PENDING' and
                          (lower(i.invitee_email) = lower(?) or
                           (cast(? as text) is not null and upper(i.invitee_student_no) = upper(cast(? as text)))))))
                  left join request_reviews rv on rv.request_id = r.id
                  left join vms v on r.resource_type = 'VM' and v.request_id = r.id and
                       (rr.resource_id = v.id or rr.id is null and not exists
                        (select 1 from request_recipients any_rr where any_rr.request_id = r.id))
                  left join llm_api_keys k on r.resource_type = 'LLM_API_KEY' and k.request_id = r.id and
                       (rr.resource_id = k.id or rr.id is null and not exists
                        (select 1 from request_recipients any_rr where any_rr.request_id = r.id))
                  left join gpu_allocations gpu on r.resource_type = 'GPU' and gpu.request_id = r.id
                 where """ + scope.guard("r.org_id") + """
                   and (r.requester_id = ? or rr.id is not null or exists
                       (select 1 from workspace_members m where m.workspace_id = r.workspace_id and m.user_id = ?))
                 order by r.id desc, rr.id
                """, (rs, row) -> new AdminUserSupportResponse.AdminUserSupportRelatedRequest(rs.getObject("public_id", UUID.class),
                        ResourceType.valueOf(rs.getString("resource_type")), RequestStatus.valueOf(rs.getString("status")),
                        rs.getObject("workspace_public_id", UUID.class), rs.getString("workspace_name"),
                        rs.getObject("org_public_id", UUID.class), rs.getString("org_name"), rs.getBoolean("applicant"),
                        rs.getObject("recipient_public_id", UUID.class), rs.getString("recipient_status") == null ? null
                                : RequestRecipientStatus.valueOf(rs.getString("recipient_status")),
                        rs.getObject("resource_public_id", UUID.class), rs.getObject("granted_end_date", LocalDate.class),
                        rs.getString("reason")), user.getId(), user.getId(), user.getId(), pendingScope.arrayParam(), pendingScope.arrayParam(), user.getEmail(), studentNo,
                studentNo, scope.arrayParam(), scope.arrayParam(), user.getId(), user.getId());
    }

    static boolean approver(AuthenticatedUser actor) {
        return actor.role() == UserRole.SYS_ADMIN || actor.role() == UserRole.ORG_ADMIN || actor.role() == UserRole.ORG_MANAGER;
    }

    static @Nullable Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static ResourceRow resourceRow(ResultSet rs, int row) throws SQLException {
        return new ResourceRow(ResourceType.valueOf(rs.getString("type")), rs.getLong("id"),
                rs.getObject("public_id", UUID.class), rs.getString("name"), rs.getString("status"),
                rs.getLong("workspace_id"), rs.getObject("workspace_public_id", UUID.class), rs.getString("workspace_name"),
                rs.getLong("org_id"), rs.getObject("org_public_id", UUID.class), rs.getString("org_name"),
                rs.getObject("request_public_id", UUID.class), rs.getObject("end_date", LocalDate.class),
                instant(rs, "expires_at"), instant(rs, "released_at"));
    }

    private record ResourceRow(ResourceType type, long id, UUID publicId, String name, String status,
            long workspaceId, UUID workspacePublicId, String workspaceName, long orgId, UUID orgPublicId,
            String orgName, @Nullable UUID requestPublicId, @Nullable LocalDate endDate,
            @Nullable Instant expiresAt, @Nullable Instant releasedAt) { }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 대상이 존재하지 않습니다.");
    }
}
