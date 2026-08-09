package kr.ac.pusan.pickle.access;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.group.GroupMember;
import kr.ac.pusan.pickle.group.GroupMemberRepository;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place that decides what standing a requester has on a VM.
 *
 * <p>Before this existed the same three steps — load the VM, look up the
 * requester's role in its owning group, choose between 404 and 403 — sat
 * copied in a dozen services, which is why the ladder could not gain a second
 * axis without a dozen edits. Every user-facing VM surface now asks here
 * instead, so per-resource access grants can replace what {@link #standing}
 * reads without touching the callers.
 *
 * <p>Admin surfaces are deliberately not routed through this class: their scope
 * is the organisation, not group membership, and mixing the two is how a bypass
 * gets written by accident.
 */
@Service
public class VmAccessService {

    private final VmRepository vmRepository;
    private final GroupMemberRepository groupMemberRepository;

    public VmAccessService(VmRepository vmRepository, GroupMemberRepository groupMemberRepository) {
        this.vmRepository = vmRepository;
        this.groupMemberRepository = groupMemberRepository;
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
        return new VmAccess(vm, standing(vm, userId));
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

    /** The role lookup itself — the one read that per-resource grants will replace. */
    private GroupMemberRole standing(Vm vm, long userId) {
        return groupMemberRepository.findByGroupIdAndUserId(vm.getGroupId(), userId)
                .map(GroupMember::getRole)
                .orElse(null);
    }

    /** The masking 404: an existing but unreachable VM reads as a missing one. */
    public static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }
}
