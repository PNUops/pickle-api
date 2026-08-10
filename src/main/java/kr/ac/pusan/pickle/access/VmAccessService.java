package kr.ac.pusan.pickle.access;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.resource.VmResourceAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place that decides what standing a requester has on a VM.
 *
 * <p>Access comes from the VM's access list and from nothing else: a rung in
 * the owning workspace no longer implies reaching the workspace's VMs. The one thing
 * workspace standing still carries is an owner's permanent read, deletion and grant
 * management — held here as a flag rather than folded into a rung, so that no
 * check for something inside the VM can be satisfied by it.
 *
 * <p>This is the VM's face of a resource-generic model: what a grant is worth is
 * worked out by {@link ResourceAccessResolver} for every type at once, and what
 * is left here is loading the VM and carrying it alongside the answer. Containers
 * and API keys arrive as siblings over the same table and the same resolver,
 * which is why grant rows are keyed by resource type rather than by vm_id.
 *
 * <p>Admin surfaces are deliberately not routed through here: their scope is
 * the organisation, not the access list, and mixing the two is how a bypass
 * gets written by accident.
 */
@Service
public class VmAccessService {

    private final VmRepository vmRepository;
    private final ResourceAccessResolver resolver;

    public VmAccessService(VmRepository vmRepository, ResourceAccessResolver resolver) {
        this.vmRepository = vmRepository;
        this.resolver = resolver;
    }

    /** Standing of {@code actor} on {@code vmId}; unknown VM answers 404. */
    @Transactional(readOnly = true)
    public VmAccess of(AuthenticatedUser actor, long vmId) {
        return of(vmId, actor.id());
    }

    /** Standing of one user on {@code vmId}; unknown VM answers 404. */
    @Transactional(readOnly = true)
    public VmAccess of(long vmId, long userId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(VmAccessService::vmNotFound);
        return of(vm, userId);
    }

    /** Standing on an already-loaded VM, for callers that resolved it first. */
    @Transactional(readOnly = true)
    public VmAccess of(Vm vm, long userId) {
        ResourceStanding standing = resolver.standing(ResourceType.VM, vm.getId(),
                vm.getWorkspaceId(), userId);
        return new VmAccess(vm, standing.grantedRole(), standing.owningWorkspaceMember(),
                standing.standingRights());
    }

    /**
     * Standing without the 404, for callers that answer with a value rather
     * than an exception — the terminal re-check and the gateway route decision.
     * Returns null when the VM itself is gone.
     */
    @Transactional(readOnly = true)
    public VmAccess find(long vmId, long userId) {
        return vmRepository.findById(vmId).map(vm -> of(vm, userId)).orElse(null);
    }

    /** The masking 404: an existing but unreachable VM reads as a missing one. */
    public static ApiException vmNotFound() {
        return VmResourceAdapter.MESSAGES.notFound();
    }
}
