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
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.request.RequestRecipientStatus;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceInvitationStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Global support metadata resolves directory relationships without granting resource access. */
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
    private final StudentSupportPolicy studentPolicy;
    private final InstitutionAuthorization authorization;
    private final StudentRelationshipPermissions relationships;

    public AdminUserSupportQueryService(JdbcTemplate jdbc, UserRepository users,
            ResourceAccessResolver resolver, ResourceAccessGrantRepository grants, Clock clock,
            StudentSupportPolicy studentPolicy, InstitutionAuthorization authorization,
            StudentRelationshipPermissions relationships) {
        this.jdbc = jdbc;
        this.users = users;
        this.resolver = resolver;
        this.grants = grants;
        this.clock = clock;
        this.studentPolicy = studentPolicy;
        this.authorization = authorization;
        this.relationships = relationships;
    }

    @Transactional(readOnly = true)
    public AdminUserSupportResponse support(AuthenticatedUser actor, UUID userId, @Nullable UUID orgId) {
        studentPolicy.requireStudentSupport(actor);
        User user = requireUser(actor, userId);
        OrgScope scope = scope(actor, orgId);
        Instant now = clock.instant();
        boolean resourcesAvailable = relationships.resourcesAvailable(actor);
        List<ResourceRow> rows = !resourcesAvailable ? List.of() : resources(actor, scope, now, false, "(exists (select 1 from workspace_members m "
                + "where m.workspace_id = x.workspace_id and m.user_id = ?) or exists "
                + "(select 1 from resource_access_grants g where g.resource_type::text = x.type "
                + "and g.resource_id = x.id and g.grantee_type = 'USER' and g.user_id = ?))", user.getId(), user.getId());
        List<AdminUserResourceAccessResponse> resources = rows.stream()
                .map(row -> access(actor, user, row, now)).toList();
        boolean workspaceAvailable = relationships.workspaceAvailable(actor);
        var workspaceFilter = relationships.workspaces(actor);
        List<Object> membershipParams = new ArrayList<>(); membershipParams.add(user.getId()); membershipParams.addAll(workspaceFilter.params());
        List<AdminUserSupportResponse.AdminUserSupportMembership> memberships = !workspaceAvailable ? null : jdbc.query("""
                select w.id, w.public_id, w.name, w.kind, m.role
                  from workspace_members m join workspaces w on w.id = m.workspace_id
                 where m.user_id = ? and w.deleted_at is null and (
                """ + workspaceFilter.sql() + ") order by w.id", (rs, row) -> {
                    UUID workspaceId = rs.getObject("public_id", UUID.class);
                    long workspaceInternalId = rs.getLong("id");
                    List<AdminUserSupportResponse.AdminUserSupportResourceCount> counts = java.util.Arrays.stream(ResourceType.values())
                            .map(type -> new AdminUserSupportResponse.AdminUserSupportResourceCount(type,
                                    relationships.completeCount(actor, type, workspaceId, scope)
                                            ? resourceCount(scope, workspaceInternalId, type) : null)).toList();
                    return new AdminUserSupportResponse.AdminUserSupportMembership(workspaceId, rs.getString("name"),
                            WorkspaceKind.valueOf(rs.getString("kind")),
                            WorkspaceMemberRole.valueOf(rs.getString("role")), counts);
                }, membershipParams.toArray());
        boolean invitationsVisible = workspaceAvailable;
        return new AdminUserSupportResponse(userId, now, memberships, invitationsVisible,
                invitationsVisible ? invitations(actor, user) : null,
                relationships.requestsAvailable(actor) ? requests(actor, user, scope) : null,
                resourcesAvailable ? resources : null, relationships.relationshipsLimited(actor));
    }

    private long resourceCount(OrgScope scope, long workspaceId, ResourceType type) {
        String table = switch (type) { case VM -> "vms"; case LLM_API_KEY -> "llm_api_keys"; case DOMAIN -> "domains"; case GPU -> "gpu_allocations"; };
        Long count = jdbc.queryForObject("select count(*) from " + table + " x where x.workspace_id = ? and "
                + scope.guard("x.org_id"), Long.class, workspaceId, scope.arrayParam(), scope.arrayParam());
        return count == null ? 0 : count;
    }

    @Transactional(readOnly = true)
    public AdminUserResourceAccessResponse diagnose(AuthenticatedUser actor, UUID userId,
            @Nullable UUID orgId, ResourceType type, UUID resourceId) {
        Instant now = clock.instant();
        List<ResourceRow> rows = resources(actor, scope(actor, orgId), now, true,
                "x.type = ? and x.public_id = ?", type.name(), resourceId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        User user = users.findByPublicId(userId).orElseThrow(AdminUserSupportQueryService::notFound);
        ResourceRow resource = rows.getFirst();
        boolean related = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from workspace_members m where m.workspace_id=? and m.user_id=?)"
                        + " or exists(select 1 from resource_access_grants g where g.resource_type::text=? and g.resource_id=? and g.grantee_type='USER' and g.user_id=?)",
                Boolean.class, resource.workspaceId(), user.getId(), type.name(), resource.id(), user.getId()));
        boolean broadNonSystem = studentPolicy.canReadStudentSupport(actor) && !user.getRole().isSysTier();
        if (!actor.role().isSysTier() && !related && !broadNonSystem) { throw notFound(); }
        return access(actor, user, resource, now);
    }

    User requireUser(AuthenticatedUser actor, UUID userId) {
        return users.findByPublicId(userId)
                .filter(user -> studentPolicy.canLookupStudent(actor, user.getRole()))
                .orElseThrow(AdminUserSupportQueryService::notFound);
    }

    private OrgScope scope(AuthenticatedUser actor, @Nullable UUID orgId) {
        Long internal = orgId == null ? null : jdbc.query("select id from orgs where public_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, orgId);
        return orgId != null && internal == null ? OrgScope.nothing() : OrgScope.of(internal);
    }

    private List<ResourceRow> resources(AuthenticatedUser actor, OrgScope scope, Instant now, boolean aclOnly,
            String predicate, Object... args) {
        var filter = aclOnly ? relationships.aclResources(actor) : relationships.resourceReferences(actor);
        List<Object> params = new ArrayList<>();
        params.add(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        params.add(scope.arrayParam());
        params.add(scope.arrayParam());
        params.addAll(filter.params());
        params.addAll(java.util.Arrays.asList(args));
        return jdbc.query("select x.*, w.public_id as workspace_public_id, w.name as workspace_name, "
                + "o.public_id as org_public_id, o.name as org_name, r.public_id as request_public_id, "
                + "r.org_id as request_org_id,r.workspace_id as request_workspace_id "
                + "from (" + RESOURCE_ROWS + ") x join workspaces w on w.id = x.workspace_id "
                + "join orgs o on o.id = x.org_id left join requests r on r.id = x.request_id "
                + "where " + scope.guard("x.org_id") + " and (" + filter.sql() + ") and " + predicate
                + " order by x.type, x.id desc", AdminUserSupportQueryService::resourceRow, params.toArray());
    }

    private AdminUserResourceAccessResponse access(AuthenticatedUser actor, User user, ResourceRow resource,
            Instant now) {
        UUID requestId = resource.requestOrgId() != null && resource.requestWorkspaceId() != null
                && relationships.request(actor, resource.type(), resource.requestOrgId(), resource.requestWorkspaceId())
                ? resource.requestPublicId() : null;
        if (!relationships.acl(actor, resource.type(), resource.orgId(), resource.workspaceId())) {
            return new AdminUserResourceAccessResponse(resource.type(), resource.publicId(), resource.name(), resource.status(),
                    resource.orgPublicId(), resource.orgName(), resource.workspacePublicId(), resource.workspaceName(), requestId,
                    user.getStatus(), null, null, null, null, null, null, null, null, null);
        }
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
        String revokeReason = !canManageResourceAccess(actor, resource)
                ? "FORBIDDEN" : resource.type() == ResourceType.DOMAIN && "REMOVED".equals(resource.status())
                ? "RESOURCE_NOT_FOUND" : personal == null ? "NO_PERSONAL_GRANT" : "ALLOWED";
        return new AdminUserResourceAccessResponse(resource.type(), resource.publicId(), resource.name(), resource.status(),
                resource.orgPublicId(), resource.orgName(), resource.workspacePublicId(), resource.workspaceName(),
                requestId, user.getStatus(), membership, personal, workspace, standing.role(),
                standing.standingRights(), active && standing.owningWorkspaceMember() && standing.role() != null,
                "ALLOWED".equals(revokeReason), revokeReason, List.copyOf(reasons));
    }

    private List<AdminUserSupportResponse.AdminUserSupportInvitation> invitations(AuthenticatedUser actor, User user) {
        String studentNo = studentPolicy.canReadStudentSupport(actor) ? user.getStudentNo() : null;
        var filter = relationships.workspaces(actor);
        List<Object> params = new ArrayList<>(java.util.Arrays.asList(user.getId(), user.getEmail(), user.getId(), user.getEmail(), studentNo, studentNo));
        params.addAll(filter.params());
        return jdbc.query("""
                select i.public_id, w.public_id as workspace_public_id, w.name, w.kind, i.role,
                       i.status, i.accepted_at,
                       case when i.accepted_user_id = ? then 'ACCEPTED'
                            when lower(i.invitee_email) = lower(?) then 'EMAIL' else 'STUDENT_NO' end as matched_by
                  from workspace_invitations i join workspaces w on w.id = i.workspace_id
                 where w.deleted_at is null and (i.accepted_user_id = ? or
                       (i.status = 'PENDING' and (lower(i.invitee_email) = lower(?) or
                        (cast(? as text) is not null and upper(i.invitee_student_no) = upper(cast(? as text))))))
                 and (
                """ + filter.sql() + ") order by i.id desc", (rs, row) -> new AdminUserSupportResponse.AdminUserSupportInvitation(rs.getObject("public_id", UUID.class),
                        rs.getObject("workspace_public_id", UUID.class), rs.getString("name"),
                        WorkspaceKind.valueOf(rs.getString("kind")), WorkspaceMemberRole.valueOf(rs.getString("role")),
                        WorkspaceInvitationStatus.valueOf(rs.getString("status")), rs.getString("matched_by"),
                        instant(rs, "accepted_at")), params.toArray());
    }

    private List<AdminUserSupportResponse.AdminUserSupportRelatedRequest> requests(AuthenticatedUser actor, User user, OrgScope scope) {
        String studentNo = studentPolicy.canReadStudentSupport(actor) ? user.getStudentNo() : null;
        OrgScope pendingScope = studentPolicy.canReadStudentSupport(actor) ? OrgScope.unrestricted() : OrgScope.nothing();
        var filter = relationships.requestRows(actor);
        List<Object> params = new ArrayList<>(java.util.Arrays.asList(user.getId(), user.getId(), user.getId(),
                pendingScope.arrayParam(), pendingScope.arrayParam(), user.getEmail(), studentNo, studentNo,
                scope.arrayParam(), scope.arrayParam()));
        params.addAll(filter.params()); params.add(user.getId()); params.add(user.getId());
        return jdbc.query("""
                select r.public_id, r.resource_type, r.status, w.public_id as workspace_public_id,
                       w.name as workspace_name, o.public_id as org_public_id, o.name as org_name,
                       r.requester_id = ? as applicant, rr.public_id as recipient_public_id,
                       rr.status as recipient_status, rr.reason, rv.granted_end_date,
                       coalesce(v.public_id, k.public_id, gpu.public_id) as resource_public_id,
                       coalesce(v.org_id,k.org_id,gpu.org_id) as linked_org_id,
                       coalesce(v.workspace_id,k.workspace_id,gpu.workspace_id) as linked_workspace_id
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
                 where """ + scope.guard("r.org_id") + " and (" + filter.sql() + ") " + """
                   and (r.requester_id = ? or rr.id is not null or exists
                       (select 1 from workspace_members m where m.workspace_id = r.workspace_id and m.user_id = ?))
                 order by r.id desc, rr.id
                """, (rs, row) -> new AdminUserSupportResponse.AdminUserSupportRelatedRequest(rs.getObject("public_id", UUID.class),
                        ResourceType.valueOf(rs.getString("resource_type")), RequestStatus.valueOf(rs.getString("status")),
                        rs.getObject("workspace_public_id", UUID.class), rs.getString("workspace_name"),
                        rs.getObject("org_public_id", UUID.class), rs.getString("org_name"), rs.getBoolean("applicant"),
                        rs.getObject("recipient_public_id", UUID.class), rs.getString("recipient_status") == null ? null
                                : RequestRecipientStatus.valueOf(rs.getString("recipient_status")),
                        resourceReference(actor, ResourceType.valueOf(rs.getString("resource_type")),
                                rs.getObject("linked_org_id", Long.class), rs.getObject("linked_workspace_id", Long.class))
                                ? rs.getObject("resource_public_id", UUID.class) : null,
                        rs.getObject("granted_end_date", LocalDate.class), rs.getString("reason")), params.toArray());
    }

    private boolean resourceReference(AuthenticatedUser actor, ResourceType type, Long institution, Long workspace) {
        return institution != null && workspace != null && (relationships.metadata(actor, type, institution, workspace)
                || relationships.acl(actor, type, institution, workspace));
    }

    private boolean canManageResourceAccess(AuthenticatedUser actor, ResourceRow resource) {
        if (actor.role() == kr.ac.pusan.pickle.user.UserRole.SYS_ADMIN) { return true; }
        InstitutionWork work = switch (resource.type()) {
            case VM -> InstitutionWork.VM_ACL_SUPPORT_MANAGE;
            case LLM_API_KEY -> InstitutionWork.LLM_ACL_SUPPORT_MANAGE;
            case DOMAIN -> InstitutionWork.DOMAIN_ACL_SUPPORT_MANAGE;
            case GPU -> InstitutionWork.GPU_ACL_SUPPORT_MANAGE;
        };
        return authorization.historicalResourceTarget(InstitutionAuthorization.group(resource.type()).orElseThrow(),
                resource.orgId(), resource.workspaceId())
                .map(target -> authorization.decision(actor, work, target).allowed()).orElse(false);
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
                rs.getObject("request_public_id", UUID.class), rs.getObject("request_org_id", Long.class),
                rs.getObject("request_workspace_id", Long.class), rs.getObject("end_date", LocalDate.class),
                instant(rs, "expires_at"), instant(rs, "released_at"));
    }

    private record ResourceRow(ResourceType type, long id, UUID publicId, String name, String status,
            long workspaceId, UUID workspacePublicId, String workspaceName, long orgId, UUID orgPublicId,
            String orgName, @Nullable UUID requestPublicId, @Nullable Long requestOrgId,
            @Nullable Long requestWorkspaceId, @Nullable LocalDate endDate,
            @Nullable Instant expiresAt, @Nullable Instant releasedAt) { }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 대상이 존재하지 않습니다.");
    }
}
