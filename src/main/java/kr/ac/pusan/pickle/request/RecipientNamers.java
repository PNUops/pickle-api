package kr.ac.pusan.pickle.request;

import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.workspace.WorkspaceMember;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import org.jspecify.annotations.Nullable;

/**
 * Who may name the people a request makes resources for: an owner of the
 * workspace, or someone who may decide the requests of the organisation the
 * request is filed under. One rule for submitting such a request and for
 * looking up a roster ahead of it, so the lookup never reaches further than
 * the submission it prepares.
 */
public final class RecipientNamers {

    private RecipientNamers() {
    }

    /**
     * @param membership the actor's membership in the workspace, if any
     * @param orgId the organisation the request is filed under, if known
     */
    public static boolean mayName(AuthenticatedUser actor, @Nullable WorkspaceMember membership,
            @Nullable Long orgId) {
        return (membership != null && membership.getRole() == WorkspaceMemberRole.OWNER)
                || RequestApprovers.mayApprove(actor, orgId);
    }
}
