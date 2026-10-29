package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.stereotype.Component;

/** Fixed student-relationship projections retain each source's institution and shared workspace scope. */
@Component
final class StudentRelationshipPermissions {
    private static final UserRole[] SYS_READERS = {UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN};
    private final InstitutionAuthorization authorization;
    private final ResourceInstitutionPermissions resources;

    StudentRelationshipPermissions(InstitutionAuthorization authorization, ResourceInstitutionPermissions resources) {
        this.authorization = authorization;
        this.resources = resources;
    }

    Sql workspaces(AuthenticatedUser actor) {
        return condition(actor, null, "w.public_id", List.of(InstitutionWork.WORKSPACE_SUPPORT_READ));
    }

    Sql vmMetadata(AuthenticatedUser actor) {
        return condition(actor, "vo.public_id", "vw.public_id", metadata(ResourceType.VM));
    }

    Sql resourceReferences(AuthenticatedUser actor) { return typed(actor, "x.type", false, false); }
    Sql aclResources(AuthenticatedUser actor) { return typed(actor, "x.type", false, true); }
    Sql requestRows(AuthenticatedUser actor) { return typed(actor, "r.resource_type::text", true, false); }

    Sql directoryInstitutionMembership(AuthenticatedUser actor, OrgScope filter) {
        Sql requests = requestRows(actor); Sql machines = vmMetadata(actor);
        List<Object> params = new ArrayList<>(filter.orgIds());
        params.addAll(filter.orgIds()); params.addAll(requests.params());
        params.addAll(filter.orgIds()); params.addAll(machines.params());
        String sql = "(exists(select 1 from user_org_roles uor where uor.user_id=u.id and " + filter.inList("uor.org_id") + ")"
                + " or (u.status='ACTIVE' and (exists(select 1 from workspace_members m join requests r on r.workspace_id=m.workspace_id"
                + " join orgs o on o.id=r.org_id join workspaces w on w.id=r.workspace_id where m.user_id=u.id and "
                + filter.inList("r.org_id") + " and (" + requests.sql() + "))"
                + " or exists(select 1 from workspace_members m join vms v on v.workspace_id=m.workspace_id"
                + " join orgs vo on vo.id=v.org_id join workspaces vw on vw.id=v.workspace_id where m.user_id=u.id and v.status<>'DELETED' and "
                + filter.inList("v.org_id") + " and (" + machines.sql() + ")))))";
        return new Sql(sql, params);
    }

    boolean workspaceAvailable(AuthenticatedUser actor) { return available(actor, List.of(InstitutionWork.WORKSPACE_SUPPORT_READ)); }
    boolean vmMetadataAvailable(AuthenticatedUser actor) { return available(actor, metadata(ResourceType.VM)); }
    boolean vmMetadataInWorkspace(AuthenticatedUser actor, UUID workspace) {
        return actor.role().isSysTier() || metadata(ResourceType.VM).stream().anyMatch(work -> authorization.sources(actor, work).stream()
                .anyMatch(source -> source.workspaceScope() != null && source.workspaceScope().includes(workspace)));
    }
    boolean resourcesAvailable(AuthenticatedUser actor) {
        return Arrays.stream(ResourceType.values()).anyMatch(type -> available(actor, references(type)));
    }
    boolean requestsAvailable(AuthenticatedUser actor) {
        return Arrays.stream(ResourceType.values()).anyMatch(type -> available(actor, requests(type)));
    }

    boolean allWorkspaces(AuthenticatedUser actor) { return all(actor, InstitutionWork.WORKSPACE_SUPPORT_READ); }
    boolean allVmMetadata(AuthenticatedUser actor) { return all(actor, InstitutionWork.VM_METADATA_SUPPORT_READ); }
    boolean detailLimited(AuthenticatedUser actor) { return !allWorkspaces(actor) || !allVmMetadata(actor); }
    boolean relationshipsLimited(AuthenticatedUser actor) {
        if (!allWorkspaces(actor)) { return true; }
        for (ResourceType type : ResourceType.values()) {
            if (!all(actor, metadata(type).get(1)) || !all(actor, requests(type).get(1))
                    || !(all(actor, acl(type).get(0)) || all(actor, acl(type).get(1)))) { return true; }
        }
        return false;
    }

    boolean metadata(AuthenticatedUser actor, ResourceType type, long institution, long workspace) {
        return metadata(type).stream().anyMatch(work -> resources.resource(actor, work, institution, workspace, true, SYS_READERS));
    }
    boolean acl(AuthenticatedUser actor, ResourceType type, long institution, long workspace) {
        return resources.resource(actor, acl(type).get(0), institution, workspace, true, SYS_READERS)
                || resources.resource(actor, acl(type).get(1), institution, workspace, true, UserRole.SYS_ADMIN);
    }
    boolean request(AuthenticatedUser actor, ResourceType type, long institution, long workspace) {
        return requests(type).stream().anyMatch(work -> resources.resource(actor, work, institution, workspace, true, SYS_READERS));
    }

    boolean completeCount(AuthenticatedUser actor, ResourceType type, UUID workspace, OrgScope filter) {
        if (actor.role().isSysTier()) { return true; }
        boolean ownKind = metadata(type).stream().anyMatch(work -> coversWorkspace(actor, work, workspace, filter));
        if (type != ResourceType.DOMAIN) { return ownKind; }
        return ownKind && (coversWorkspace(actor, InstitutionWork.VM_METADATA_SUPPORT_READ, workspace, filter)
                || coversWorkspace(actor, InstitutionWork.VM_PUBLICATION_READ, workspace, filter));
    }

    private boolean coversWorkspace(AuthenticatedUser actor, InstitutionWork work, UUID workspace, OrgScope filter) {
        UUID filteredInstitution = !filter.isUnrestricted() && filter.orgIds().size() == 1
                ? authorization.institutionTarget(filter.orgIds().getFirst()).map(target -> target.institutionId()).orElse(null) : null;
        return authorization.sources(actor, work).stream().anyMatch(source -> source.workspaceScope() != null
                && source.workspaceScope().includes(workspace)
                && (source.requiredInstitutionId() == null || source.requiredInstitutionId().equals(filteredInstitution)));
    }

    private boolean available(AuthenticatedUser actor, List<InstitutionWork> work) {
        return actor.role().isSysTier() || work.stream().anyMatch(item -> !authorization.sources(actor, item).isEmpty());
    }
    private boolean all(AuthenticatedUser actor, InstitutionWork work) {
        return actor.role().isSysTier() || authorization.sources(actor, work).stream().anyMatch(source -> source.workspaceScope() != null
                && source.workspaceScope().mode() == InstitutionWorkspaceScope.Mode.ALL);
    }

    private Sql typed(AuthenticatedUser actor, String typeColumn, boolean request, boolean aclOnly) {
        List<String> branches = new ArrayList<>(); List<Object> params = new ArrayList<>();
        for (ResourceType type : ResourceType.values()) {
            Sql scoped = condition(actor, "o.public_id", "w.public_id", request ? requests(type) : aclOnly ? acl(type) : references(type));
            branches.add("(" + typeColumn + "='" + type.name() + "' and (" + scoped.sql() + "))");
            params.addAll(scoped.params());
        }
        return new Sql("(" + String.join(" or ", branches) + ")", params);
    }

    private Sql condition(AuthenticatedUser actor, String institutionColumn, String workspaceColumn, List<InstitutionWork> work) {
        if (actor.role().isSysTier()) { return new Sql("true", List.of()); }
        List<String> branches = new ArrayList<>(); List<Object> params = new ArrayList<>();
        for (InstitutionWork item : work) {
            for (var source : authorization.sources(actor, item)) {
                List<String> terms = new ArrayList<>();
                if (source.requiredInstitutionId() != null) {
                    if (institutionColumn == null) { continue; }
                    terms.add(institutionColumn + "=?::uuid"); params.add(source.requiredInstitutionId());
                }
                if (source.workspaceScope() == null) { continue; }
                terms.add(workspaceColumn + " is not null");
                if (source.workspaceScope().mode() == InstitutionWorkspaceScope.Mode.LISTED) {
                    List<UUID> ids = source.workspaceScope().workspaceIds().stream().sorted().toList();
                    if (ids.isEmpty()) { continue; }
                    terms.add(workspaceColumn + " in (" + String.join(",", ids.stream().map(id -> "?::uuid").toList()) + ")");
                    params.addAll(ids);
                }
                branches.add("(" + String.join(" and ", terms) + ")");
            }
        }
        return new Sql(branches.isEmpty() ? "false" : "(" + String.join(" or ", branches) + ")", params);
    }

    private static List<InstitutionWork> references(ResourceType type) {
        List<InstitutionWork> work = new ArrayList<>(metadata(type)); work.addAll(acl(type)); return work;
    }
    private static List<InstitutionWork> metadata(ResourceType type) {
        return switch (type) {
            case VM -> List.of(InstitutionWork.VM_METADATA_READ, InstitutionWork.VM_METADATA_SUPPORT_READ);
            case LLM_API_KEY -> List.of(InstitutionWork.LLM_METADATA_READ, InstitutionWork.LLM_METADATA_SUPPORT_READ);
            case DOMAIN -> List.of(InstitutionWork.DOMAIN_METADATA_READ, InstitutionWork.DOMAIN_METADATA_SUPPORT_READ);
            case GPU -> List.of(InstitutionWork.GPU_METADATA_READ, InstitutionWork.GPU_METADATA_SUPPORT_READ);
        };
    }
    private static List<InstitutionWork> acl(ResourceType type) {
        return switch (type) {
            case VM -> List.of(InstitutionWork.VM_ACL_SUPPORT_READ, InstitutionWork.VM_ACL_SUPPORT_MANAGE);
            case LLM_API_KEY -> List.of(InstitutionWork.LLM_ACL_SUPPORT_READ, InstitutionWork.LLM_ACL_SUPPORT_MANAGE);
            case DOMAIN -> List.of(InstitutionWork.DOMAIN_ACL_SUPPORT_READ, InstitutionWork.DOMAIN_ACL_SUPPORT_MANAGE);
            case GPU -> List.of(InstitutionWork.GPU_ACL_SUPPORT_READ, InstitutionWork.GPU_ACL_SUPPORT_MANAGE);
        };
    }
    private static List<InstitutionWork> requests(ResourceType type) {
        return switch (type) {
            case VM -> List.of(InstitutionWork.VM_REQUEST_READ, InstitutionWork.VM_REQUEST_SUPPORT_READ);
            case LLM_API_KEY -> List.of(InstitutionWork.LLM_REQUEST_READ, InstitutionWork.LLM_REQUEST_SUPPORT_READ);
            case DOMAIN -> List.of(InstitutionWork.DOMAIN_REQUEST_READ, InstitutionWork.DOMAIN_REQUEST_SUPPORT_READ);
            case GPU -> List.of(InstitutionWork.GPU_REQUEST_READ, InstitutionWork.GPU_REQUEST_SUPPORT_READ);
        };
    }

    record Sql(String sql, List<Object> params) { Sql { params = List.copyOf(params); } }
}
