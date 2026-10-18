package kr.ac.pusan.pickle.vm;

import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.resource.VmResourceAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.ResourceInstitutionPermissions;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.stereotype.Component;

/** VM administrator targets retain separate metadata, command, policy and publication authority. */
@Component
public class AdminVmAccess {
    private final VmRepository vmRepository;
    private final ResourceInstitutionPermissions permissions;

    public AdminVmAccess(VmRepository vmRepository, ResourceInstitutionPermissions permissions) {
        this.vmRepository = vmRepository; this.permissions = permissions;
    }

    public AuthenticatedUser currentForWrite(AuthenticatedUser actor) { return permissions.currentForWrite(actor); }

    public Vm requireReadableVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        if (allowedWorkIds(actor, vm).isEmpty()) { throw vmNotFound(); }
        return vm;
    }

    public java.util.Set<String> allowedWorkIds(AuthenticatedUser actor, Vm vm) {
        return kr.ac.pusan.pickle.security.institution.InstitutionResourceQueries.VM_REFERENCE_WORKS.stream()
                .filter(work -> {
                    if (work == InstitutionWork.VM_DELETION_SCHEDULE || work == InstitutionWork.VM_ACL_SUPPORT_MANAGE) {
                        return permissions.resource(actor, work, vm.getOrgId(), vm.getWorkspaceId(),
                                work == InstitutionWork.VM_ACL_SUPPORT_MANAGE, UserRole.SYS_ADMIN);
                    }
                    return work.write() ? canWrite(actor, vm, work) : canRead(actor, vm, work);
                }).map(InstitutionWork::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean canReadMetadata(AuthenticatedUser actor, Vm vm) {
        return canRead(actor, vm, InstitutionWork.VM_METADATA_READ)
                || canRead(actor, vm, InstitutionWork.VM_METADATA_SUPPORT_READ);
    }

    public boolean canRead(AuthenticatedUser actor, Vm vm, InstitutionWork work) {
        return permissions.resource(actor, work, vm.getOrgId(), vm.getWorkspaceId(), true,
                UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN);
    }

    public boolean canWrite(AuthenticatedUser actor, Vm vm, InstitutionWork work) {
        return permissions.resource(actor, work, vm.getOrgId(), vm.getWorkspaceId(), false,
                UserRole.SYS_MANAGER, UserRole.SYS_ADMIN);
    }

    public boolean canDelete(AuthenticatedUser actor, Vm vm) {
        return permissions.resource(actor, InstitutionWork.VM_DELETION_SCHEDULE, vm.getOrgId(), vm.getWorkspaceId(), false,
                UserRole.SYS_ADMIN);
    }

    public Vm requireReadablePolicyVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        if (!canRead(actor, vm, InstitutionWork.VM_NETWORK_POLICY_READ) && !canWrite(actor, vm, InstitutionWork.VM_NETWORK_POLICY_WRITE)) { throw vmNotFound(); }
        return vm;
    }

    public Vm requireWritableVm(AuthenticatedUser actor, UUID vmId, InstitutionWork work) {
        Vm vm = findOrNotFound(vmId);
        if (!canWrite(actor, vm, work)) { throw vmNotFound(); }
        return vm;
    }

    public Vm requireAdministeredVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        if (!canDelete(actor, vm)) { throw vmNotFound(); }
        return vm;
    }

    private Vm findOrNotFound(UUID vmId) { return vmRepository.findByPublicId(vmId).orElseThrow(AdminVmAccess::vmNotFound); }
    public static ApiException vmNotFound() { return VmResourceAdapter.MESSAGES.notFound(); }
}
