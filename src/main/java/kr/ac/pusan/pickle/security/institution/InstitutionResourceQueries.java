package kr.ac.pusan.pickle.security.institution;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fixed resource collections apply complete source predicates before their callers page or count. */
@Component
public class InstitutionResourceQueries {
    public static final List<InstitutionWork> VM_REFERENCE_WORKS = List.of(
            InstitutionWork.VM_METADATA_READ, InstitutionWork.VM_METADATA_SUPPORT_READ,
            InstitutionWork.VM_OBSERVE, InstitutionWork.VM_POWER, InstitutionWork.VM_PERIOD,
            InstitutionWork.VM_DELETION_SCHEDULE, InstitutionWork.VM_NETWORK_POLICY_READ,
            InstitutionWork.VM_NETWORK_POLICY_WRITE, InstitutionWork.VM_PUBLICATION_READ,
            InstitutionWork.VM_PUBLICATION_OPERATE, InstitutionWork.VM_CUSTOM_DOMAIN_VERIFY,
            InstitutionWork.VM_TERMINAL_SESSION_READ, InstitutionWork.VM_ACL_SUPPORT_READ,
            InstitutionWork.VM_ACL_SUPPORT_MANAGE);
    public static final List<InstitutionWork> GPU_REFERENCE_WORKS = List.of(
            InstitutionWork.GPU_METADATA_READ, InstitutionWork.GPU_METADATA_SUPPORT_READ,
            InstitutionWork.GPU_OBSERVE, InstitutionWork.GPU_OBSERVE_SUPPORT_READ,
            InstitutionWork.GPU_PERIOD, InstitutionWork.GPU_RECALL,
            InstitutionWork.GPU_ACL_SUPPORT_READ, InstitutionWork.GPU_ACL_SUPPORT_MANAGE);
    public static final List<InstitutionWork> DOMAIN_REFERENCE_WORKS = List.of(
            InstitutionWork.DOMAIN_METADATA_READ, InstitutionWork.DOMAIN_METADATA_SUPPORT_READ,
            InstitutionWork.DOMAIN_OBSERVE, InstitutionWork.DOMAIN_RENEWAL, InstitutionWork.DOMAIN_RELEASE,
            InstitutionWork.DOMAIN_ACL_SUPPORT_READ, InstitutionWork.DOMAIN_ACL_SUPPORT_MANAGE);
    public static final List<InstitutionWork> PUBLICATION_REFERENCE_WORKS = List.of(
            InstitutionWork.VM_PUBLICATION_READ, InstitutionWork.VM_METADATA_SUPPORT_READ,
            InstitutionWork.VM_PUBLICATION_OPERATE, InstitutionWork.VM_CUSTOM_DOMAIN_VERIFY);
    public static final List<InstitutionWork> WORKSPACE_REFERENCE_WORKS = List.of(
            InstitutionWork.WORKSPACE_SUPPORT_READ, InstitutionWork.WORKSPACE_INFO_UPDATE,
            InstitutionWork.WORKSPACE_MEMBERS_MANAGE, InstitutionWork.WORKSPACE_INVITATIONS_MANAGE,
            InstitutionWork.WORKSPACE_OWNERS_MANAGE, InstitutionWork.WORKSPACE_TERMINATE,
            InstitutionWork.WORKSPACE_RECIPIENT_CANCEL);
    public static final List<InstitutionWork> REQUEST_WORKSPACE_REFERENCE_WORKS = List.of(
            InstitutionWork.VM_REQUEST_DECIDE, InstitutionWork.VM_ROSTER_ISSUE,
            InstitutionWork.LLM_REQUEST_DECIDE, InstitutionWork.LLM_ROSTER_ISSUE,
            InstitutionWork.DOMAIN_REQUEST_DECIDE, InstitutionWork.GPU_REQUEST_DECIDE);
    private final JdbcTemplate jdbc;
    private final InstitutionAuthorization authorization;

    public InstitutionResourceQueries(JdbcTemplate jdbc, InstitutionAuthorization authorization) {
        this.jdbc = jdbc; this.authorization = authorization;
    }

    public List<Long> vmMetadata(AuthenticatedUser actor) {
        return ids(actor, "select v.id from vms v left join orgs o on o.id=v.org_id left join workspaces w on w.id=v.workspace_id where ",
                "o.public_id", "w.public_id", InstitutionWork.VM_METADATA_READ, InstitutionWork.VM_METADATA_SUPPORT_READ);
    }

    public List<Long> vmPeriodReferences(AuthenticatedUser actor) {
        return ids(actor, "select v.id from vms v left join orgs o on o.id=v.org_id left join workspaces w on w.id=v.workspace_id where ",
                "o.public_id", "w.public_id", InstitutionWork.VM_METADATA_READ, InstitutionWork.VM_METADATA_SUPPORT_READ, InstitutionWork.VM_PERIOD);
    }

    public List<Long> vmReferences(AuthenticatedUser actor) {
        return ids(actor, "select v.id from vms v left join orgs o on o.id=v.org_id left join workspaces w on w.id=v.workspace_id where ",
                "o.public_id", "w.public_id", VM_REFERENCE_WORKS.toArray(InstitutionWork[]::new));
    }

    public List<Long> gpuAllocations(AuthenticatedUser actor) {
        if (actor.role().isSysTier()) { return jdbc.queryForList("select id from gpu_allocations", Long.class); }
        List<Object> args = new ArrayList<>();
        String metadataOrCommand = predicate(actor, "o.public_id", "w.public_id", args,
                InstitutionWork.GPU_METADATA_READ, InstitutionWork.GPU_METADATA_SUPPORT_READ,
                InstitutionWork.GPU_PERIOD, InstitutionWork.GPU_RECALL,
                InstitutionWork.GPU_ACL_SUPPORT_READ, InstitutionWork.GPU_ACL_SUPPORT_MANAGE);
        String observe = predicate(actor, "o.public_id", "w.public_id", args,
                InstitutionWork.GPU_OBSERVE, InstitutionWork.GPU_OBSERVE_SUPPORT_READ);
        return jdbc.queryForList("select a.id from gpu_allocations a join orgs o on o.id=a.org_id join workspaces w on w.id=a.workspace_id "
                + "where (" + metadataOrCommand + ") or (a.connection_status='ATTACHED' and a.attached_at is not null and (" + observe + "))", Long.class, args.toArray());
    }

    /** A direct institution grant never proves coverage of every VM in an institution-less workspace. */
    public boolean canReadWorkspaceVmTotal(AuthenticatedUser actor, UUID resolvedWorkspace) {
        if (actor.role().isSysTier()) { return true; }
        return authorization.sources(actor,InstitutionWork.VM_METADATA_SUPPORT_READ).stream()
                .anyMatch(source->source.requiredInstitutionId()==null && source.workspaceScope()!=null
                        && source.workspaceScope().includes(resolvedWorkspace));
    }

    public List<Long> workspaces(AuthenticatedUser actor) {
        return ids(actor, "select w.id from workspaces w where ", null, "w.public_id", WORKSPACE_REFERENCE_WORKS.toArray(InstitutionWork[]::new));
    }

    public List<Long> workspaceMetadata(AuthenticatedUser actor) {
        return ids(actor, "select w.id from workspaces w where ", null, "w.public_id", InstitutionWork.WORKSPACE_SUPPORT_READ);
    }

    public List<Long> domains(AuthenticatedUser actor) {
        if (actor.role().isSysTier()) { return jdbc.queryForList("select id from domains", Long.class); }
        List<Object> args = new ArrayList<>();
        String external = predicate(actor, "o.public_id", "w.public_id", args,
                InstitutionWork.DOMAIN_METADATA_READ, InstitutionWork.DOMAIN_METADATA_SUPPORT_READ);
        String vm = predicate(actor, "o.public_id", "w.public_id", args,
                InstitutionWork.VM_PUBLICATION_READ, InstitutionWork.VM_METADATA_SUPPORT_READ);
        return jdbc.queryForList("select d.id from domains d left join orgs o on o.id=d.org_id "
                + "left join workspaces w on w.id=d.workspace_id where (d.kind='EXTERNAL' and (" + external
                + ")) or (d.kind in ('CUSTOM','PLATFORM','AUTO') and (" + vm + "))", Long.class, args.toArray());
    }

    public List<Long> domainReferences(AuthenticatedUser actor) {
        if (actor.role().isSysTier()) { return jdbc.queryForList("select id from domains", Long.class); }
        List<Object> args = new ArrayList<>();
        String external = predicate(actor, "o.public_id", "w.public_id", args, DOMAIN_REFERENCE_WORKS.toArray(InstitutionWork[]::new));
        String vm = predicate(actor, "o.public_id", "w.public_id", args,
                InstitutionWork.VM_PUBLICATION_READ, InstitutionWork.VM_METADATA_SUPPORT_READ, InstitutionWork.VM_PUBLICATION_OPERATE);
        String custom = predicate(actor, "o.public_id", "w.public_id", args, InstitutionWork.VM_CUSTOM_DOMAIN_VERIFY);
        return jdbc.queryForList("select d.id from domains d left join orgs o on o.id=d.org_id left join workspaces w on w.id=d.workspace_id "
                + "where (d.kind='EXTERNAL' and (" + external + ")) or (d.kind in ('CUSTOM','PLATFORM','AUTO') and (" + vm + ")) or (d.kind='CUSTOM' and (" + custom + "))", Long.class, args.toArray());
    }

    public List<Long> routes(AuthenticatedUser actor) {
        if (actor.role().isSysTier()) { return jdbc.queryForList("select id from routes", Long.class); }
        List<Object> args = new ArrayList<>();
        String metadata = predicate(actor, "o.public_id", "w.public_id", args,
                InstitutionWork.VM_PUBLICATION_READ, InstitutionWork.VM_METADATA_SUPPORT_READ);
        List<String> commands = new ArrayList<>();
        for (InstitutionAuthorizationSource source : authorization.sources(actor, InstitutionWork.VM_PUBLICATION_OPERATE)) {
            List<Object> branchArgs = new ArrayList<>();
            String scope = workspaceScope(source.workspaceScope(), "w.public_id", branchArgs);
            if (scope == null) { continue; }
            if (source.requiredInstitutionId() == null) { continue; }
            args.add(source.requiredInstitutionId()); args.addAll(branchArgs);
            commands.add("(o.public_id=?::uuid and " + scope + " and (w.deleted_at is null or r.status='REMOVED'))");
        }
        String command = commands.isEmpty() ? "false" : String.join(" or ", commands);
        return jdbc.queryForList("select r.id from routes r join domains d on d.id=r.domain_id join vms v on v.id=d.vm_id "
                + "left join orgs o on o.id=d.org_id left join workspaces w on w.id=d.workspace_id "
                + "where d.kind in ('CUSTOM','PLATFORM','AUTO') and ((" + metadata + ") or (" + command + "))", Long.class, args.toArray());
    }

    public List<Long> certificates(AuthenticatedUser actor) {
        if (actor.role().isSysTier()) { return jdbc.queryForList("select id from certificates", Long.class); }
        List<Object> args = new ArrayList<>();
        String scoped = predicate(actor, "o.public_id", "w.public_id", args, InstitutionWork.VM_PUBLICATION_READ);
        boolean shared = !authorization.sources(actor, InstitutionWork.SHARED_CERTIFICATE_READ).isEmpty();
        return jdbc.queryForList("select c.id from certificates c left join domains d on d.id=c.domain_id "
                + "left join vms v on v.id=d.vm_id left join orgs o on o.id=v.org_id "
                + "left join workspaces w on w.id=v.workspace_id where (c.domain_id is null and " + shared
                + ") or (c.domain_id is not null and (" + scoped + "))", Long.class, args.toArray());
    }

    /** New-request selectors verify a source institution and live typed scope, not prior resource ownership. */
    public List<Long> requestWorkspaces(AuthenticatedUser actor, UUID selectedInstitution) {
        if (actor.role().isSysTier()) {
            if (actor.role() != kr.ac.pusan.pickle.user.UserRole.SYS_ADMIN) { return List.of(); }
            return selectedInstitution == null
                    ? jdbc.queryForList("select id from workspaces where deleted_at is null", Long.class)
                    : jdbc.queryForList("select id from workspaces where deleted_at is null and exists(select 1 from orgs where public_id=?::uuid)", Long.class, selectedInstitution);
        }
        List<Object> args = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        for (InstitutionWork work : REQUEST_WORKSPACE_REFERENCE_WORKS) {
            for (InstitutionAuthorizationSource source : authorization.sources(actor, work)) {
                if (selectedInstitution != null && !selectedInstitution.equals(source.requiredInstitutionId())) { continue; }
                String scope = workspaceScope(source.workspaceScope(), "w.public_id", args);
                if (scope != null) {
                    args.add(source.source().institutionId());
                    branches.add("(" + scope + " and exists(select 1 from orgs ro where ro.public_id=?::uuid))");
                }
            }
        }
        return branches.isEmpty() ? List.of() : jdbc.queryForList("select w.id from workspaces w where w.deleted_at is null and ("
                + String.join(" or ", branches) + ")", Long.class, args.toArray());
    }

    public List<Long> announcementWorkspaces(AuthenticatedUser actor, UUID selectedInstitution) {
        if (actor.role().isSysTier()) {
            if (actor.role() != kr.ac.pusan.pickle.user.UserRole.SYS_ADMIN) { return List.of(); }
            return selectedInstitution == null
                    ? jdbc.queryForList("select id from workspaces where deleted_at is null", Long.class)
                    : jdbc.queryForList("select w.id from workspaces w where w.deleted_at is null and exists(select 1 from orgs qo where qo.public_id=?::uuid and "
                            + kr.ac.pusan.pickle.orgs.OrgMembershipSql.workspaceLinkedToOrg("w.id", "qo.id") + ")", Long.class, selectedInstitution);
        }
        List<Object> args = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        for (InstitutionAuthorizationSource source : authorization.sources(actor, InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND)) {
            if (selectedInstitution != null && !selectedInstitution.equals(source.source().institutionId())) { continue; }
            String scope = workspaceScope(source.workspaceScope(), "w.public_id", args);
            if (scope == null) { continue; }
            args.add(source.source().institutionId());
            branches.add("(" + scope + " and exists(select 1 from orgs qo where qo.public_id=?::uuid and "
                    + kr.ac.pusan.pickle.orgs.OrgMembershipSql.workspaceLinkedToOrg("w.id", "qo.id") + "))");
        }
        return branches.isEmpty() ? List.of() : jdbc.queryForList("select w.id from workspaces w where w.deleted_at is null and ("
                + String.join(" or ", branches) + ")", Long.class, args.toArray());
    }

    /** These are command references; each later endpoint must still resolve its actual request institution. */
    public java.util.Set<String> workspaceCommandWorkIds(AuthenticatedUser actor, long workspace, UUID selectedInstitution) {
        UUID target = jdbc.queryForObject("select public_id from workspaces where id=? and deleted_at is null", UUID.class, workspace);
        return workspaceCommandWorkIdsForResolvedWorkspace(actor, workspace, target, selectedInstitution);
    }

    /** The provider validated source institutions; the caller selected the supplied live workspace row. */
    public java.util.Set<String> workspaceCommandWorkIdsForResolvedWorkspace(AuthenticatedUser actor, long workspace,
            UUID target, UUID selectedInstitution) {
        java.util.Set<String> result = new java.util.HashSet<>();
        if (actor.role().isSysTier()) {
            if (actor.role() == kr.ac.pusan.pickle.user.UserRole.SYS_ADMIN) {
                REQUEST_WORKSPACE_REFERENCE_WORKS.forEach(work -> result.add(work.id()));
                result.add(InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND.id());
            }
            return java.util.Set.copyOf(result);
        }
        for (InstitutionWork work : REQUEST_WORKSPACE_REFERENCE_WORKS) {
            for (InstitutionAuthorizationSource source : authorization.sources(actor, work)) {
                if (selectedInstitution != null && !selectedInstitution.equals(source.requiredInstitutionId())) { continue; }
                if (authorization.decision(actor, work, InstitutionWorkTarget.resource(source.source().institutionId(), work.group(), target)).allowed()) {
                    result.add(work.id());
                }
            }
        }
        if (authorization.sources(actor, InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND).isEmpty()) { return java.util.Set.copyOf(result); }
        java.util.Set<UUID> institutions = java.util.Set.copyOf(jdbc.queryForList("select qo.public_id from orgs qo where "
                + kr.ac.pusan.pickle.orgs.OrgMembershipSql.workspaceLinkedToOrg("?", "qo.id"), UUID.class, workspace, workspace));
        if (authorization.decision(actor, InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND,
                InstitutionWorkTarget.qualifiedWorkspace(target, institutions)).allowed()) { result.add(InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND.id()); }
        return java.util.Set.copyOf(result);
    }

    private static String workspaceScope(InstitutionWorkspaceScope scope, String column, List<Object> args) {
        if (scope == null) { return null; }
        if (scope.mode() == InstitutionWorkspaceScope.Mode.ALL) { return column + " is not null"; }
        if (scope.workspaceIds().isEmpty()) { return null; }
        List<UUID> ids = scope.workspaceIds().stream().sorted().toList();
        args.addAll(ids);
        return column + " in (" + String.join(",", ids.stream().map(id -> "?::uuid").toList()) + ")";
    }

    private List<Long> ids(AuthenticatedUser actor, String sql, String org, String workspace, InstitutionWork... work) {
        List<Object> args = new ArrayList<>();
        String condition = actor.role().isSysTier() ? "true" : predicate(actor, org, workspace, args, work);
        return jdbc.queryForList(sql + condition, Long.class, args.toArray());
    }

    private String predicate(AuthenticatedUser actor, String org, String workspace,
            List<Object> args, InstitutionWork... work) {
        List<String> branches = new ArrayList<>();
        for (InstitutionWork item : work) {
            for (InstitutionAuthorizationSource source : authorization.sources(actor, item)) {
                List<String> terms = new ArrayList<>();
                if (item.write() && item.boundary() != InstitutionWork.Boundary.RESOURCE_SUPPORT) {
                    terms.add(workspace.substring(0, workspace.indexOf('.')) + ".deleted_at is null");
                }
                if (source.requiredInstitutionId() != null) {
                    if (org == null) { continue; }
                    terms.add(org + "=?::uuid"); args.add(source.requiredInstitutionId());
                }
                InstitutionWorkspaceScope scope = source.workspaceScope();
                if (scope != null) {
                    terms.add(workspace + " is not null");
                    if (scope.mode() == InstitutionWorkspaceScope.Mode.LISTED) {
                        if (scope.workspaceIds().isEmpty()) { continue; }
                        List<UUID> ids = scope.workspaceIds().stream().sorted().toList();
                        terms.add(workspace + " in (" + String.join(",", ids.stream().map(id -> "?::uuid").toList()) + ")");
                        args.addAll(ids);
                    }
                }
                branches.add("(" + String.join(" and ", terms) + ")");
            }
        }
        return branches.isEmpty() ? "false" : "(" + String.join(" or ", branches) + ")";
    }
}
