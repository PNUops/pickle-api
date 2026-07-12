package kr.ac.pusan.pickle.sshgw;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.config.SshGatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sole access gate for {@code /internal/**} (docs/api/internal.md common
 * conventions, docs/plan/07). Runs three checks, in order, and passes the
 * request through only when all pass:
 *
 * <ol>
 *   <li><b>Source IP allowlist</b> — the TCP peer must be the sshgw LXC
 *       ({@code pickle.sshgw.allowed-source-ip}); defence in depth on top of the
 *       vmbr1 firewall. The real peer ({@code getRemoteAddr()}) is used, never a
 *       spoofable X-Forwarded-For — the internal endpoint is called directly on
 *       :8080, not through the console nginx.</li>
 *   <li><b>Bearer token</b> — a constant-time match against
 *       {@code PICKLE_SSHGW_TOKEN}. <b>Fails closed</b> when the token is unset,
 *       so a mis-provisioned prod profile rejects every call rather than
 *       accepting an empty bearer. Never a user JWT.</li>
 *   <li><b>Rate limit</b> — per source IP, via the shared PG limiter, to bound
 *       abuse if the token leaks inside vmbr1.</li>
 * </ol>
 *
 * <p>Auth failures return a generic 401 (token) or 403 (source) without
 * revealing which specific check failed — in particular a missing and a wrong
 * token are indistinguishable.</p>
 */
public class InternalSshGatewayAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String RATE_LIMIT_SCOPE = "sshgw_route";

    private final SshGatewayProperties properties;
    private final RateLimitService rateLimitService;
    private final ProblemJsonWriter problemJsonWriter;

    public InternalSshGatewayAuthFilter(SshGatewayProperties properties,
            RateLimitService rateLimitService, ProblemJsonWriter problemJsonWriter) {
        this.properties = properties;
        this.rateLimitService = rateLimitService;
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String peer = request.getRemoteAddr();

        if (!properties.allowedSourceIp().equals(peer)) {
            forbidden(request, response);
            return;
        }
        if (!tokenMatches(request)) {
            unauthorized(request, response);
            return;
        }
        try {
            rateLimitService.hit(RATE_LIMIT_SCOPE, peer, properties.rateLimitPerMinute());
        } catch (ApiException e) {
            rateLimited(request, response, e);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Constant-time bearer comparison; fails closed when no token is set. */
    private boolean tokenMatches(HttpServletRequest request) {
        if (properties.tokenUnset()) {
            return false;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String presented = header.substring(BEARER_PREFIX.length()).trim();
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                properties.token().getBytes(StandardCharsets.UTF_8));
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        problemJsonWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                ErrorCodes.AUTH_TOKEN_INVALID, "인증이 필요합니다", "유효한 인증 토큰이 필요합니다.");
    }

    private void forbidden(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        problemJsonWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                ErrorCodes.ACCESS_DENIED, "접근이 거부되었습니다", "허용되지 않은 접근입니다.");
    }

    private void rateLimited(HttpServletRequest request, HttpServletResponse response, ApiException e)
            throws IOException {
        if (e.getRetryAfterSeconds() != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
        }
        problemJsonWriter.write(request, response, 429,
                ErrorCodes.RATE_LIMITED, e.getTitle(), e.getDetail());
    }
}
