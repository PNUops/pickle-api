package kr.ac.pusan.pickle.access;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.resource.VmResourceAdapter;
import kr.ac.pusan.pickle.vm.Vm;
import org.springframework.http.HttpStatus;

/**
 * One requester's standing on one VM: a {@link ResourceStanding} with the VM it
 * was computed for, for the VM services that need both.
 *
 * <p>Three outcomes decide every one of those services: someone outside the
 * owning workspace with no grant is answered 404 so VM ids cannot be probed; a
 * member of the owning workspace without a grant may see that the VM exists but
 * nothing inside it; a grantee acts at their rung. What each outcome means is
 * decided by {@code ResourceStanding} for every resource type at once; this
 * record only adds the VM and the words the VM refuses in.
 *
 * @param vm                the VM in question
 * @param grantedRole       the rung from the access list, or null with no grant
 * @param owningWorkspaceMember whether the requester belongs to the owning workspace
 * @param standingRights    whether they are an owner of that workspace, which
 *                          carries deletion and grant management on every
 *                          resource the workspace owns, and nothing inside them
 */
public record VmAccess(Vm vm, ResourceRole grantedRole, boolean owningWorkspaceMember,
        boolean standingRights) {

    /** This standing without the VM — the part every resource type shares. */
    public ResourceStanding standing() {
        return new ResourceStanding(grantedRole, owningWorkspaceMember, standingRights);
    }

    /**
     * The rung this requester acts at, which comes from the access list and
     * nowhere else. Null when the list does not name them — including for an
     * owner of the workspace, whose standing rights are deliberately not a rung
     * (operator, 2026-08-09): they may see that the VM exists, delete it and
     * manage who reaches it, but its address, its guest account and its
     * published ports are inside, and inside needs a grant.
     */
    public ResourceRole role() {
        return standing().role();
    }

    public boolean atLeast(ResourceRole min) {
        return standing().atLeast(min);
    }

    /**
     * True when this VM may only be shown as name, state and who owns it. A
     * workspace owner with no grant lands here too, with the access list and
     * deletion still open to them via {@link #manages()}.
     */
    public boolean limited() {
        return standing().limited();
    }

    /** May manage the access list and delete: a resource owner, or a workspace owner. */
    public boolean manages() {
        return standing().manages();
    }

    /**
     * 404 for someone the VM's existence is masked from, and an honest 403 for
     * a workspace member who can already see it listed but holds no grant.
     */
    public Vm requireVisible() {
        standing().requireVisible(VmResourceAdapter.MESSAGES);
        return vm;
    }

    /** Visible first, then 403 in the caller's words below {@code min}. */
    public Vm requireAtLeast(ResourceRole min, String title, String detail) {
        requireVisible();
        if (!atLeast(min)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.WORKSPACE_ROLE_INSUFFICIENT,
                    title, detail);
        }
        return vm;
    }
}
