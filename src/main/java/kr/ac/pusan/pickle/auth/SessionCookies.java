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
 *
 * <p>Both names carry the {@code __Host-} prefix. The console moved to a
 * hostname under a public suffix, so the cookie "site" now spans every host in
 * that university zone rather than a domain we own outright: a sibling host
 * could otherwise set a {@code Domain=}-scoped cookie of the same name that
 * shadows ours, and every reader here takes the first match. A browser refuses
 * to store a {@code __Host-} cookie that carries {@code Domain=}, so the
 * shadowing is impossible to express rather than merely detected.
 */
@Component
public class SessionCookies {

    public static final String REFRESH_COOKIE = "__Host-pickle_refresh";
    static final String CSRF_COOKIE = RefreshCsrfFilter.CSRF_COOKIE;
    /**
     * The {@code __Host-} prefix requires {@code Path=/}, so both cookies are
     * root-scoped. The refresh cookie loses the path narrowing it used to have;
     * it stays {@code HttpOnly} and only two endpoints read it, and the reverse
     * proxy strips cookies from the web-terminal path so the lower-privilege
     * bridge still never receives one.
     */
    static final String COOKIE_PATH = "/";

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
     * {@code __Host-pickle_refresh=<opaque>; Path=/; Max-Age=1209600; HttpOnly;
     * Secure; SameSite=Strict} per contract.
     */
    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .build();
    }

    /**
     * CSRF double-submit cookie, reissued on every login/refresh: {@code
     * __Host-pickle_csrf=<128-bit random>; Path=/; Max-Age=1209600; Secure;
     * SameSite=Strict} per contract. Deliberately NOT HttpOnly — the console
     * script must read it to echo the value in the {@code X-Pickle-Csrf} header
     * ({@link RefreshCsrfFilter} enforces the match).
     */
    private ResponseCookie csrfCookie(String value, Duration maxAge) {
        return ResponseCookie.from(CSRF_COOKIE, value)
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .httpOnly(false)
                .secure(true)
                .sameSite("Strict")
                .build();
    }
}
