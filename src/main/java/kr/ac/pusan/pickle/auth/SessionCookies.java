package kr.ac.pusan.pickle.auth;

import java.time.Duration;
import java.util.List;
import kr.ac.pusan.pickle.config.AuthProperties;
import kr.ac.pusan.pickle.security.RefreshCsrfFilter;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Session cookie pair issued on every login/refresh/password-change and cleared
 * on logout/withdraw. Extracted so the {@code me} account endpoints reuse the
 * exact refresh + CSRF double-submit cookies the auth flow defines (contract
 * securityScheme / {@link RefreshCsrfFilter}).
 */
@Component
public class SessionCookies {

    public static final String REFRESH_COOKIE = "pickle_refresh";
    static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    static final String CSRF_COOKIE = RefreshCsrfFilter.CSRF_COOKIE;
    static final String CSRF_COOKIE_PATH = "/";

    private final AuthProperties authProperties;

    public SessionCookies(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /** Two {@code Set-Cookie} headers: a fresh refresh token + a rotated CSRF token. */
    public List<String> issued(String refreshToken) {
        Duration ttl = authProperties.refreshTokenTtl();
        return List.of(refreshCookie(refreshToken, ttl).toString(),
                csrfCookie(TokenHasher.newCsrfToken(), ttl).toString());
    }

    /** Two {@code Set-Cookie} headers clearing both cookies ({@code Max-Age=0}). */
    public List<String> cleared() {
        return List.of(refreshCookie("", Duration.ZERO).toString(),
                csrfCookie("", Duration.ZERO).toString());
    }

    /**
     * {@code pickle_refresh=<opaque>; Path=/api/v1/auth; Max-Age=1209600;
     * HttpOnly; Secure; SameSite=Lax} per contract.
     */
    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .build();
    }

    /**
     * CSRF double-submit cookie, reissued on every login/refresh: {@code
     * pickle_csrf=<128-bit random>; Path=/; Max-Age=1209600; Secure;
     * SameSite=Lax} per contract. Deliberately NOT HttpOnly — the console script
     * must read it to echo the value in the {@code X-Pickle-Csrf} header
     * ({@link RefreshCsrfFilter} enforces the match).
     */
    private ResponseCookie csrfCookie(String value, Duration maxAge) {
        return ResponseCookie.from(CSRF_COOKIE, value)
                .path(CSRF_COOKIE_PATH)
                .maxAge(maxAge)
                .httpOnly(false)
                .secure(true)
                .sameSite("Lax")
                .build();
    }
}
