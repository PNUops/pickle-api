package kr.ac.pusan.pickle.access;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.vm.Vm;
import org.springframework.http.HttpStatus;

/**
 * One requester's standing on one VM: the VM plus the role they hold on it, or
 * a null role when they hold none.
 *
 * <p>The two refusals a caller can raise from here are the whole masking policy:
 * someone with no standing at all is answered 404 so VM ids cannot be probed,
 * while someone who can already see the VM but sits below the required rung is
 * answered an honest 403. Callers supply the 403 wording because it names the
 * operation they were attempting; they do not decide which of the two applies.
 */
public record VmAccess(Vm vm, GroupMemberRole role) {

    /** Unknown VM and no standing are indistinguishable to the caller (404). */
    public Vm requireVisible() {
        if (role == null) {
            throw VmAccessService.vmNotFound();
        }
        return vm;
    }

    public boolean atLeast(GroupMemberRole min) {
        return role != null && role.atLeast(min);
    }

    /** 404 without standing, 403 in the caller's words below {@code min}. */
    public Vm requireAtLeast(GroupMemberRole min, String title, String detail) {
        requireVisible();
        if (!atLeast(min)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    title, detail);
        }
        return vm;
    }
}
