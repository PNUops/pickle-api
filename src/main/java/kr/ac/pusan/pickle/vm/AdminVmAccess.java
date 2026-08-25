package kr.ac.pusan.pickle.vm;

import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.resource.VmResourceAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.stereotype.Component;

/**
 * Org-scope resolution for the admin surfaces that act on a single VM. The two
 * entry points are split by what the caller does with the VM, because the read
 * side and the write side answer to different rules: a cross-org target is a
 * 404 for both today, and only the read side is meant to widen. Keeping one
 * method for both is how widening a read would silently widen power control.
 *
 * <p>The 404 (rather than 403) is deliberate on both sides: a cross-org target
 * answers exactly as an unknown id does, so the existence of other orgs' VMs
 * stays private.
 */
@Component
public class AdminVmAccess {

    private final VmRepository vmRepository;

    public AdminVmAccess(VmRepository vmRepository) {
        this.vmRepository = vmRepository;
    }

    /**
     * Read surfaces: admin VM detail and its event history.
     */
    public Vm requireReadableVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        requireSameOrg(actor, vm);
        return vm;
    }

    /**
     * Write surfaces: period update and the four power interventions (start,
     * shutdown, reboot, force stop), plus the scheduled-deletion pair.
     */
    public Vm requireWritableVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        requireSameOrg(actor, vm);
        return vm;
    }

    private Vm findOrNotFound(UUID vmId) {
        return vmRepository.findByPublicId(vmId).orElseThrow(AdminVmAccess::vmNotFound);
    }

    private static void requireSameOrg(AuthenticatedUser actor, Vm vm) {
        if (actor.role().isOrgTier() && !actor.manages(vm.getOrgId())) {
            throw vmNotFound();
        }
    }

    /** The one wording for an unreachable VM, shared with the access machinery. */
    private static ApiException vmNotFound() {
        return VmResourceAdapter.MESSAGES.notFound();
    }
}
