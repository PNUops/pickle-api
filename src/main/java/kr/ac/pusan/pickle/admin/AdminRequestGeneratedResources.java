package kr.ac.pusan.pickle.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.AdminRequestGeneratedResourceResponse;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.publishing.Domain;
import kr.ac.pusan.pickle.publishing.DomainRepository;
import kr.ac.pusan.pickle.publishing.PublicationAssembler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A request-scoped observation of resource rows, never a name lookup or a completion assertion. */
@Component
class AdminRequestGeneratedResources {
    private final JdbcTemplate jdbc;
    private final DomainRepository domains;
    private final PublicationAssembler publication;
    @org.springframework.beans.factory.annotation.Autowired
    private StudentSupportPolicy supportPolicy;
    @org.springframework.beans.factory.annotation.Autowired
    private kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions permissions;

    AdminRequestGeneratedResources(JdbcTemplate jdbc, DomainRepository domains, PublicationAssembler publication) {
        this.jdbc = jdbc; this.domains = domains; this.publication = publication;
    }

    /** The existing administrator directory boundary, applied to persisted target identities only. */
    Map<UUID, Boolean> recipientUserReadFlags(Request request, AuthenticatedUser actor) {
        Map<UUID, Boolean> flags = new LinkedHashMap<>();
        jdbc.query("""
                select distinct u.public_id, u.role::text as role
                  from request_recipients rr join users u on u.id = rr.user_id
                 where rr.request_id = ?
                 order by u.public_id
                """, rs -> {
                    UserRole targetRole = UserRole.valueOf(rs.getString("role"));
                    flags.put(rs.getObject("public_id", UUID.class),
                            supportPolicy.canReadStudentSupport(actor) && (actor.role().isSysTier() || !targetRole.isSysTier()));
                }, request.getId());
        return Map.copyOf(flags);
    }

    List<AdminRequestGeneratedResourceResponse> of(Request request, Instant now, AuthenticatedUser actor) {
        Map<Long, Domain> linkedDomains = request.getResourceType() == ResourceType.DOMAIN
                ? domains.findByRequestId(request.getId()).stream().collect(Collectors.toMap(Domain::getId, Function.identity()))
                : Map.of();
        return jdbc.query("""
                select x.*, o.public_id as org_public_id, w.public_id as workspace_public_id,
                       rr.public_id as recipient_public_id, n.public_id as node_public_id, n.name as node_name
                  from (
                    select id, public_id, 'VM' as type, name, status::text as state,
                           org_id, workspace_id, request_id, created_at, updated_at, node_id,
                           null::timestamptz as released_at, null::timestamptz as renew_due_at from vms where request_id = ?
                    union all
                    select id, public_id, 'LLM_API_KEY', name,
                           case when status in ('PENDING','ACTIVE','SUSPENDED') and expires_at <= ?
                                then 'EXPIRED' else status::text end,
                           org_id, workspace_id, request_id, created_at, updated_at, null::bigint,
                           null::timestamptz, null::timestamptz from llm_api_keys where request_id = ?
                    union all
                    select id, public_id, 'GPU', name, status::text, org_id, workspace_id,
                           request_id, created_at, updated_at, null::bigint, null::timestamptz, null::timestamptz
                      from gpu_allocations where request_id = ?
                    union all
                    select id, public_id, 'DOMAIN', fqdn, status::text, org_id, workspace_id,
                           request_id, created_at, updated_at, null::bigint, released_at, renew_due_at
                      from domains where request_id = ?
                  ) x join orgs o on o.id = x.org_id join workspaces w on w.id = x.workspace_id
                  left join request_recipients rr on rr.request_id = x.request_id and rr.resource_id = x.id
                  left join nodes n on n.id = x.node_id
                 where x.type = ? and x.org_id = ?
                 order by x.type, x.id
                """, (rs, row) -> new AdminRequestGeneratedResourceResponse(rs.getObject("public_id", UUID.class),
                        ResourceType.valueOf(rs.getString("type")), rs.getString("name"), rs.getString("state"),
                        rs.getObject("org_public_id", UUID.class), rs.getObject("workspace_public_id", UUID.class),
                        rs.getObject("recipient_public_id", UUID.class),
                        canReadDetail(actor, request.getResourceType(), rs.getObject("org_id", Long.class), rs.getObject("workspace_id", Long.class)) ? rs.getObject("node_public_id", UUID.class) : null,
                        canReadDetail(actor, request.getResourceType(), rs.getObject("org_id", Long.class), rs.getObject("workspace_id", Long.class)) ? rs.getString("node_name") : null,
                        canReadDetail(actor, request.getResourceType(), rs.getObject("org_id", Long.class),
                                rs.getObject("workspace_id", Long.class)) && (!"DOMAIN".equals(rs.getString("type")) || !"REMOVED".equals(rs.getString("state"))),
                        AdminUserSupportQueryService.instant(rs, "released_at"), AdminUserSupportQueryService.instant(rs, "renew_due_at"),
                        linkedDomains.containsKey(rs.getLong("id")) ? publication.reservedUntil(linkedDomains.get(rs.getLong("id"))) : null,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()), request.getId(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                request.getId(), request.getId(), request.getId(), request.getResourceType().name(), request.getOrgId());
    }
    private boolean canReadDetail(AuthenticatedUser actor, ResourceType type, Long org, Long workspace) {
        var work = switch (type) {
            case VM -> kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_METADATA_READ;
            case LLM_API_KEY -> kr.ac.pusan.pickle.security.institution.InstitutionWork.LLM_METADATA_READ;
            case DOMAIN -> kr.ac.pusan.pickle.security.institution.InstitutionWork.DOMAIN_METADATA_READ;
            case GPU -> kr.ac.pusan.pickle.security.institution.InstitutionWork.GPU_METADATA_READ;
        };
        var support = switch (type) {
            case VM -> kr.ac.pusan.pickle.security.institution.InstitutionWork.VM_METADATA_SUPPORT_READ;
            case LLM_API_KEY -> kr.ac.pusan.pickle.security.institution.InstitutionWork.LLM_METADATA_SUPPORT_READ;
            case DOMAIN -> kr.ac.pusan.pickle.security.institution.InstitutionWork.DOMAIN_METADATA_SUPPORT_READ;
            case GPU -> kr.ac.pusan.pickle.security.institution.InstitutionWork.GPU_METADATA_SUPPORT_READ;
        };
        return permissions.resource(actor, work, org, workspace, true, UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)
                || permissions.resource(actor, support, org, workspace, true, UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN);
    }
}
