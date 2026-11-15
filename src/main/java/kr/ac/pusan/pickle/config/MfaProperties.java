package kr.ac.pusan.pickle.config;

import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 2FA policy tunables ({@code pickle.mfa.*}).
 *
 * <p>An account covered by enforcement that has not enrolled is restricted to
 * the enrollment/auth/profile endpoints until it enrolls: a scope restriction,
 * not a login block. The two tiers have separate switches so that the org tier
 * can be deployed with enforcement off and switched on later, after its
 * administrators have been told and the unenrolled ones counted.
 *
 * @param enforceAdmin when true (prod), the system tier (SYS_ADMIN,
 *        SYS_MANAGER, SYS_VIEWER) must enroll. Off in dev/test.
 * @param enforceOrgAdmin when true, the org tier (ORG_ADMIN, ORG_MANAGER,
 *        ORG_VIEWER) must enroll as well. Off by default in every profile.
 */
@ConfigurationProperties(prefix = "pickle.mfa")
public record MfaProperties(boolean enforceAdmin, boolean enforceOrgAdmin) {

    /** Whether an account holding {@code role} must be enrolled before it may act. */
    public boolean requiresEnrollment(UserRole role) {
        return (enforceAdmin && role.isSysTier()) || (enforceOrgAdmin && role.isOrgTier());
    }
}
