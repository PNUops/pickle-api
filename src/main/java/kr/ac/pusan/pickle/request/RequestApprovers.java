package kr.ac.pusan.pickle.request;

import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;

/**
 * Who may decide a request of one organisation: the same answer the approve
 * endpoint gives through its role gate and its org scope together. A system
 * administrator decides everywhere; the org tier decides where it operates
 * (ORG_ADMIN or ORG_MANAGER); nobody else, the system manager and every
 * viewer included.
 */
public final class RequestApprovers {

    private RequestApprovers() {
    }

    public static boolean mayApprove(AuthenticatedUser actor, Long orgId) {
        if (actor == null || orgId == null) {
            return false;
        }
        if (actor.role() == UserRole.SYS_ADMIN) {
            return true;
        }
        return actor.role().isOrgTier() && actor.operates(orgId);
    }
}
