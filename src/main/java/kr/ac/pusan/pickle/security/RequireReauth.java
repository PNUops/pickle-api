package kr.ac.pusan.pickle.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler as sudo-mode protected (contract v0.24.0): the request must
 * carry a valid {@code X-Reauth-Token} (issued by {@code POST /auth/reverify})
 * or it is refused with 403 {@code REAUTH_REQUIRED} by {@code ReauthInterceptor}.
 * The springdoc customizer advertises the header + 403 on annotated operations,
 * so the contract cannot silently omit the requirement.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireReauth {
}
