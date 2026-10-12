package kr.ac.pusan.pickle.vm;

import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.resource.VmResourceAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.stereotype.Component;

/**
 * VM lookup separates global support metadata reads from institution operations
 * and administration. Unknown targets keep the common not-found response.
 */
@Component
public class AdminVmAccess {

    private final VmRepository vmRepository;

    public AdminVmAccess(VmRepository vmRepository) {
        this.vmRepository = vmRepository;
    }

    /**
     * Read surfaces: global admin VM detail and its event history; {@link #requireWritableVm} is the narrower question of where it
     * may act.
     */
    public Vm requireReadableVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        kr.ac.pusan.pickle.admin.StudentSupportPolicy.requireRead(actor);
        return vm;
    }

    /** Raw network policies retain the institution read boundary. */
    public Vm requireReadablePolicyVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        kr.ac.pusan.pickle.admin.StudentSupportPolicy.requireRead(actor);
        if (actor.role().isOrgTier() && !actor.reads(vm.getOrgId())) { throw vmNotFound(); }
        return vm;
    }

    /**
     * Write surfaces the org tier shares: period update and the four power
     * interventions (start, shutdown, reboot, force stop). The institution
     * tier must hold ORG_MANAGER or ORG_ADMIN in the actual VM institution.
     */
    public Vm requireWritableVm(AuthenticatedUser actor, UUID vmId) {
        Vm vm = findOrNotFound(vmId);
        if (actor.role().isOrgTier() && !actor.operates(vm.getOrgId())) {
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
