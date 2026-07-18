package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 2FA policy tunables ({@code pickle.mfa.*}).
 *
 * @param enforceAdmin when true (prod), admin-tier accounts that are not yet
 *        2FA-enrolled are restricted to enrollment/auth/profile endpoints until
 *        they enroll (launch gate G5). Off in dev/test.
 */
@ConfigurationProperties(prefix = "pickle.mfa")
public record MfaProperties(boolean enforceAdmin) {
}
