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
     * Read surfaces: admin VM detail and its event history. Every admin tier
     * reads every organisation's VMs (operator decision, 2026-08-25), so this
     * is a lookup and nothing else. {@link #requireWritableVm} is where the org
     * boundary still stands.
     */
    public Vm requireReadableVm(AuthenticatedUser actor, UUID vmId) {
        return findOrNotFound(vmId);
    }

    /**
     * Write surfaces the org tier shares: period update and the four power
     * interventions (start, shutdown, reboot, force stop). ORG_MANAGER holds
     * these, so any role in the VM's organisation is enough.
     */
    public Vm requireWritableVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        if (actor.role().isOrgTier() && !actor.manages(vm.getOrgId())) {
            throw vmNotFound();
        }
        return vm;
    }

    /**
     * Write surfaces reserved to ORG_ADMIN: the scheduled-deletion pair. The
     * controller gate cannot make this distinction — it sees the effective
     * role, which an account that administers some other organisation already
     * has — so the organisation-level role is checked here.
     */
    public Vm requireAdministeredVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        if (actor.role().isOrgTier() && !actor.administers(vm.getOrgId())) {
            throw vmNotFound();
        }
        return vm;
    }

    private Vm findOrNotFound(UUID vmId) {
        return vmRepository.findByPublicId(vmId).orElseThrow(AdminVmAccess::vmNotFound);
    }

    /** The one wording for an unreachable VM, shared with the access machinery. */
    private static ApiException vmNotFound() {
        return VmResourceAdapter.MESSAGES.notFound();
    }
}
