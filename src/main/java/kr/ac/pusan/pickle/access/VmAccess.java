package kr.ac.pusan.pickle.access;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.vm.Vm;
import org.springframework.http.HttpStatus;

/**
 * One requester's standing on one VM.
 *
 * <p>Three outcomes live here, and they are the whole visibility policy:
 * someone outside the owning group with no grant is answered 404 so VM ids
 * cannot be probed; a member of the owning group without a grant may see that
 * the VM exists but nothing inside it; a grantee acts at their rung.
 *
 * @param vm                the VM in question
 * @param grantedRole       the rung from the access list, or null with no grant
 * @param owningGroupMember whether the requester belongs to the owning group
 * @param standingRights    whether they are an owner of that group, which
 *                          carries deletion and grant management on every
 *                          resource the group owns, and nothing inside them
 */
public record VmAccess(Vm vm, ResourceRole grantedRole, boolean owningGroupMember,
        boolean standingRights) {

    /**
     * The rung this requester acts at, which comes from the access list and
     * nowhere else. Null when the list does not name them — including for an
     * owner of the group, whose standing rights are deliberately not a rung
     * (operator, 2026-08-09): they may see that the VM exists, delete it and
     * manage who reaches it, but its address, its guest account and its
     * published ports are inside, and inside needs a grant.
     */
    public ResourceRole role() {
        return grantedRole;
    }

    public boolean atLeast(ResourceRole min) {
        ResourceRole role = role();
        return role != null && role.atLeast(min);
    }

    /**
     * True when this VM may only be shown as name, state and who owns it. A
     * group owner with no grant lands here too, with the access list and
     * deletion still open to them via {@link #manages()}.
     */
    public boolean limited() {
        return role() == null && owningGroupMember;
    }

    /** May manage the access list and delete: a resource owner, or a group owner. */
    public boolean manages() {
        return standingRights || grantedRole == ResourceRole.OWNER;
    }

    /**
     * 404 for someone the VM's existence is masked from, and an honest 403 for
     * a group member who can already see it listed but holds no grant.
     */
    public Vm requireVisible() {
        if (role() != null) {
            return vm;
        }
        if (owningGroupMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    "이 VM에 접근할 권한이 없습니다",
                    "이 VM의 접근 목록에 등록되어 있지 않습니다. 자원 소유자에게 접근 권한을 요청해 주세요.");
        }
        throw VmAccessService.vmNotFound();
    }

    /** Visible first, then 403 in the caller's words below {@code min}. */
    public Vm requireAtLeast(ResourceRole min, String title, String detail) {
        requireVisible();
        if (!atLeast(min)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    title, detail);
        }
        return vm;
    }
}
