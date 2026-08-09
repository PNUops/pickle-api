package kr.ac.pusan.pickle.access;

import java.util.List;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRepository;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.http.HttpStatus;
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
 * <p>This is the VM adapter of a resource-generic model: containers and API
 * keys are meant to arrive as siblings of this class over the same table, which
 * is why grant rows are keyed by resource type rather than by vm_id.
 *
 * <p>Admin surfaces are deliberately not routed through here: their scope is
 * the organisation, not the access list, and mixing the two is how a bypass
 * gets written by accident.
 */
@Service
public class VmAccessService {

    private final VmRepository vmRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ResourceAccessGrantRepository grantRepository;

    public VmAccessService(VmRepository vmRepository, WorkspaceMemberRepository workspaceMemberRepository,
            ResourceAccessGrantRepository grantRepository) {
        this.vmRepository = vmRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.grantRepository = grantRepository;
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
        WorkspaceMemberRole membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(vm.getWorkspaceId(), userId)
                .map(WorkspaceMember::getRole)
                .orElse(null);
        return new VmAccess(vm, grantedRole(vm.getId(), userId, membership != null),
                membership != null, membership == WorkspaceMemberRole.OWNER);
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

    /**
     * The strongest rung the access list gives this person: their own grant and
     * the workspace-wide one, whichever is higher.
     *
     * <p>A grant counts only while its holder is still in the owning workspace.
     * Losing membership already deletes their grants, so this changes nothing
     * in practice — it is here so that a missed cleanup cannot leave someone
     * reaching a VM of a workspace they left.
     */
    private ResourceRole grantedRole(long vmId, long userId, boolean owningWorkspaceMember) {
        if (!owningWorkspaceMember) {
            return null;
        }
        ResourceRole best = null;
        List<ResourceAccessGrant> grants = grantRepository
                .findByResourceTypeAndResourceIdOrderByIdAsc(ResourceType.VM, vmId);
        for (ResourceAccessGrant grant : grants) {
            boolean applies = grant.getGranteeType() == AccessGranteeType.WORKSPACE
                    || Long.valueOf(userId).equals(grant.getUserId());
            if (applies && (best == null || grant.getRole().atLeast(best))) {
                best = grant.getRole();
            }
        }
        return best;
    }

    /** The masking 404: an existing but unreachable VM reads as a missing one. */
    public static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }
}
