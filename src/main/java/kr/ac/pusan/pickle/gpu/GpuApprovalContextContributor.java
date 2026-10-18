package kr.ac.pusan.pickle.gpu;

import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.ApprovalContextContributor;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.GpuContext;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthorization;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.stereotype.Component;

/** Platform decision references stay separate from the complete global allocation count. */
@Component
public class GpuApprovalContextContributor implements ApprovalContextContributor {
    private final GpuStore store;
    private final InstitutionAuthorization authorization;
    private final ResourceInstitutionPermissions permissions;
    public GpuApprovalContextContributor(GpuStore store, InstitutionAuthorization authorization,
            ResourceInstitutionPermissions permissions) {
        this.store = store; this.authorization = authorization; this.permissions = permissions;
    }
    @Override public ResourceType type() { return ResourceType.GPU; }
    @Override public Contribution contribute(Request request, List<Long> applicantWorkspaceIds, AuthenticatedUser actor) {
        if (request.getResourceType() != ResourceType.GPU) { return new Contribution(null, null, null, null); }
        var spec = store.requestSpec(request.getId());
        boolean reference = java.util.stream.Stream.of(InstitutionWork.GPU_REQUEST_READ,
                InstitutionWork.GPU_REQUEST_SUPPORT_READ, InstitutionWork.GPU_REQUEST_DECIDE)
                .anyMatch(work -> permissions.resource(actor, work, request.getOrgId(), request.getWorkspaceId(), false,
                        UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN));
        Long available = reference ? store.jdbc().queryForObject("""
                select count(*) from gpus g join nodes n on n.id=g.node_id
                 where g.status='ACTIVE' and n.status='ACTIVE'
                   and not exists(select 1 from gpu_allocations a where a.gpu_id=g.id and a.status in ('ALLOCATED','RELEASING'))
                """, Long.class) : null;
        boolean globalQueue = actor.role().isSysTier()
                || authorization.sources(actor, InstitutionWork.GPU_METADATA_SUPPORT_READ).stream()
                    .anyMatch(source -> source.requiredInstitutionId() == null && source.workspaceScope() != null
                            && source.workspaceScope().mode() == InstitutionWorkspaceScope.Mode.ALL);
        Long queued = globalQueue ? store.jdbc().queryForObject(
                "select count(*) from gpu_allocations where status='QUEUED'", Long.class) : null;
        return new Contribution(null, null,
                new GpuContext(available, queued, spec.leaseHours(), vmReference(actor, request, reference)), null);
    }
    private String vmReference(AuthenticatedUser actor, Request request, boolean reference) {
        return store.jdbc().query("""
                select v.org_id,v.workspace_id,v.name from gpu_request_details d
                  join vms v on v.id=d.vm_id where d.request_id=?
                """, (rs, row) -> {
                    long institution = rs.getLong("org_id");
                    Long actualInstitution = rs.wasNull() ? null : institution;
                    long workspace = rs.getLong("workspace_id");
                    boolean sameTarget = reference && java.util.Objects.equals(actualInstitution, request.getOrgId())
                            && java.util.Objects.equals(workspace, request.getWorkspaceId());
                    boolean metadata = permissions.resource(actor, InstitutionWork.VM_METADATA_READ, actualInstitution, workspace, true,
                            UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN)
                            || permissions.resource(actor, InstitutionWork.VM_METADATA_SUPPORT_READ, actualInstitution, workspace, true);
                    return sameTarget || metadata ? rs.getString("name") : null;
                }, request.getId()).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

}
