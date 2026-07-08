package kr.ac.pusan.pickle.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF double-submit-cookie check for the two endpoints that authenticate via
 * the {@code pickle_refresh} cookie instead of a Bearer token (docs/plan/07,
 * contract v0.3.1): {@code POST /api/v1/auth/refresh} and
 * {@code POST /api/v1/auth/logout}. The {@code X-Pickle-Csrf} header must
 * equal the {@code pickle_csrf} cookie; otherwise 403 {@code AUTH_CSRF_INVALID}.
 *
 * <p>The CSRF token value needs no server-side state or session binding: a
 * cross-site attacker can neither read our cookies (to copy the value into the
 * header) nor set cookies for our origin (to plant a matching pair), so a
 * matching header+cookie proves the request was issued by same-origin script.
 * That is the double-submit-cookie principle; comparison is constant-time via
 * {@link MessageDigest#isEqual} to avoid leaking the value byte-by-byte.
 */
@Component
public class RefreshCsrfFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-Pickle-Csrf";
    public static final String CSRF_COOKIE = "pickle_csrf";

    private static final Set<String> PROTECTED_PATHS =
            Set.of("/api/v1/auth/refresh", "/api/v1/auth/logout");

    private final ProblemJsonWriter problemJsonWriter;

    public RefreshCsrfFilter(ProblemJsonWriter problemJsonWriter) {
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod())
                && PROTECTED_PATHS.contains(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(CSRF_HEADER);
        String cookie = csrfCookieValue(request);
        if (header == null || header.isEmpty() || cookie == null || cookie.isEmpty()
                || !MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                        cookie.getBytes(StandardCharsets.UTF_8))) {
            problemJsonWriter.write(request, response, HttpStatus.FORBIDDEN.value(),
                    ErrorCodes.AUTH_CSRF_INVALID, "CSRF 검증에 실패했습니다",
                    "요청의 CSRF 토큰이 없거나 올바르지 않습니다. 페이지를 새로 고친 뒤 다시 시도해 주세요.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String csrfCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (CSRF_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
