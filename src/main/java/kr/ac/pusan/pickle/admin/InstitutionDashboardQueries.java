package kr.ac.pusan.pickle.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Fixed dashboard bindings retain actual institution and workspace in each source branch. */
@Component
public final class InstitutionDashboardQueries {
    private final InstitutionAuthorization authorization;
    public InstitutionDashboardQueries(InstitutionAuthorization authorization) { this.authorization = authorization; }

    public Filter vmMetadata(AuthenticatedUser actor, @Nullable UUID org) {
        return direct(actor, org, "v", InstitutionWork.VM_METADATA_READ);
    }

    public Filter publications(AuthenticatedUser actor, @Nullable UUID org) {
        return direct(actor, org, "v", InstitutionWork.VM_PUBLICATION_READ);
    }

    /** Allocation history never borrows a global support or FULL key-cost exception. */
    public Filter allocation(AuthenticatedUser actor, @Nullable UUID org) {
        if (actor.role().isSysTier()) { return system(org, "v"); }
        List<Object> args = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        authorization.authority(actor).ifPresent(authority -> InstitutionQuerySourceConstraints.vmAllocationSources(authority).sources().forEach(source -> {
            if (org == null || org.equals(source.requiredInstitutionId())) {
                branches.add(branch("v", source.requiredInstitutionId(), source.workspaceScope(), args));
            }
        }));
        return filter(branches, args);
    }

    public Filter requests(AuthenticatedUser actor, @Nullable UUID org) {
        if (actor.role().isSysTier()) { return system(org, "q"); }
        List<String> branches = new ArrayList<>(); List<Object> args = new ArrayList<>();
        for (ResourceType type : ResourceType.values()) {
            InstitutionWork work = switch (type) {
                case VM -> InstitutionWork.VM_REQUEST_READ;
                case LLM_API_KEY -> InstitutionWork.LLM_REQUEST_READ;
                case DOMAIN -> InstitutionWork.DOMAIN_REQUEST_READ;
                case GPU -> InstitutionWork.GPU_REQUEST_READ;
            };
            Filter typed = direct(actor, org, "q", work);
            if (typed.available()) { branches.add("(q.resource_type='" + type.name() + "' and " + typed.sql() + ")"); args.addAll(typed.args()); }
        }
        return filter(branches, args);
    }

    private Filter direct(AuthenticatedUser actor, @Nullable UUID org, String alias, InstitutionWork work) {
        if (actor.role().isSysTier()) { return system(org, alias); }
        List<String> branches = new ArrayList<>(); List<Object> args = new ArrayList<>();
        for (var source : authorization.sources(actor, work)) {
            if (source.requiredInstitutionId() != null && source.workspaceScope() != null
                    && (org == null || org.equals(source.requiredInstitutionId()))) {
                branches.add(branch(alias, source.requiredInstitutionId(), source.workspaceScope(), args));
            }
        }
        return filter(branches, args);
    }

    private static String branch(String alias, UUID org, InstitutionWorkspaceScope scope, List<Object> args) {
        args.add(org);
        String sql = "((select public_id from orgs where id=" + alias + ".org_id)=?::uuid and "
                + "(select public_id from workspaces where id=" + alias + ".workspace_id) is not null";
        if (scope.mode() == InstitutionWorkspaceScope.Mode.LISTED) {
            List<UUID> ids = scope.workspaceIds().stream().sorted().toList();
            if (ids.isEmpty()) { throw new IllegalArgumentException("Empty LISTED sources must be excluded before SQL construction"); }
            sql += " and (select public_id from workspaces where id=" + alias + ".workspace_id) in ("
                    + String.join(",", ids.stream().map(id -> "?::uuid").toList()) + ")";
            args.addAll(ids);
        }
        return sql + ")";
    }

    private static Filter system(@Nullable UUID org, String alias) {
        return org == null ? new Filter("true", List.of(), true)
                : new Filter("(select public_id from orgs where id=" + alias + ".org_id)=?::uuid", List.of(org), true);
    }
    private static Filter filter(List<String> branches, List<Object> args) {
        return new Filter(branches.isEmpty() ? "false" : "(" + String.join(" or ", branches) + ")", List.copyOf(args), !branches.isEmpty());
    }
    public record Filter(String sql, List<Object> args, boolean available) {
        public Object[] with(Object... trailing) {
            List<Object> values = new ArrayList<>(args); values.addAll(java.util.Arrays.asList(trailing)); return values.toArray();
        }
    }
}
