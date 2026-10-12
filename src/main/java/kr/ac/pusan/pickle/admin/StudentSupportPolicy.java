package kr.ac.pusan.pickle.admin;

import java.util.EnumSet;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/** Global support visibility is separate from institution supply and account administration. */
public final class StudentSupportPolicy {
    private static final EnumSet<UserRole> READERS = EnumSet.of(UserRole.ORG_VIEWER, UserRole.ORG_MANAGER,
            UserRole.ORG_ADMIN, UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN);

    public static boolean canRead(AuthenticatedUser actor) { return READERS.contains(actor.role()); }

    public static boolean canReadUser(AuthenticatedUser actor, UserRole target) {
        return canRead(actor) && (!actor.role().isOrgTier() || !target.isSysTier());
    }

    public static boolean canCorrectProfile(AuthenticatedUser actor, UserRole target) {
        return actor.role() == UserRole.SYS_ADMIN || !target.isSysTier()
                && (actor.role() == UserRole.ORG_ADMIN || actor.role() == UserRole.ORG_MANAGER);
    }

    public static void requireRead(AuthenticatedUser actor) {
        if (!canRead(actor)) { throw forbidden(); }
    }

    public static void requireProfileCorrection(AuthenticatedUser actor, UserRole target) {
        if (!canCorrectProfile(actor, target)) { throw forbidden(); }
    }

    public static boolean canEnableAccount(AuthenticatedUser actor) { return actor.role() == UserRole.SYS_ADMIN; }

    public static void requireEnableImpact(AuthenticatedUser actor) {
        if (!canEnableAccount(actor)) { throw forbidden(); }
    }

    public static OrgScope filterScope(AuthenticatedUser actor, @Nullable UUID orgId, @Nullable Long internalOrgId) {
        requireRead(actor);
        return orgId != null && internalOrgId == null ? OrgScope.nothing() : OrgScope.of(internalOrgId);
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                "접근 권한이 없습니다", "이 지원 작업을 수행할 권한이 없습니다.");
    }

    private StudentSupportPolicy() { }
}
