package kr.ac.pusan.pickle.llm;

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
import kr.ac.pusan.pickle.common.web.BodyCappingRequest;
import kr.ac.pusan.pickle.config.LlmGatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sole access gate for the LLM gateway surface ({@code /internal/llm/**}).
 * Deliberately NOT the sshgw internal filter (that chain is one token, one
 * pinned source, one shared global bucket — reusing it would hand a
 * compromised gateway the sshgw token's slug→VM oracle and let a usage
 * backlog 429 every user's SSH auth) and not the relay chain (per-client
 * hashed tokens exist for many off-host relays, which does not apply to a
 * single infra-bridge peer). Checks, in order:
 *
 * <ol>
 *   <li><b>Sub-path</b> — only the three known calls (sync/usage/bodies)
 *       exist on this surface; anything else answers a generic 403.</li>
 *   <li><b>Source pin</b> — the TCP peer ({@code getRemoteAddr()}, never a
 *       spoofable X-Forwarded-For; the endpoint is called directly on :8080)
 *       must be the LLM gateway LXC.</li>
 *   <li><b>Static bearer</b> — constant-time match against the configured
 *       token, or against the previous token while a rotation overlap is in
 *       flight. <b>Fails closed</b> when the current token is unset, so a
 *       mis-provisioned profile rejects every call rather than accepting an
 *       empty bearer.</li>
 *   <li><b>Per-sub-path rate bucket</b> — {@code llm_sync} / {@code llm_usage}
 *       / {@code llm_bodies}, three buckets on purpose: the three calls have
 *       rates an order of magnitude apart, and one shared bucket would let
 *       the loud two throttle the one that carries authorization.</li>
 *   <li><b>Per-sub-path body cap</b> — a declared Content-Length over the cap
 *       answers 413, and the input stream is wrapped so a chunked body is
 *       hard-capped at the same byte count.</li>
 * </ol>
 */
public class LlmGatewayAuthFilter extends OncePerRequestFilter {

    static final String SYNC_RATE_LIMIT_SCOPE = "llm_sync";
    static final String USAGE_RATE_LIMIT_SCOPE = "llm_usage";
    static final String BODIES_RATE_LIMIT_SCOPE = "llm_bodies";

    /** One peer, one subject: the source pin already narrows the caller. */
    private static final String RATE_LIMIT_SUBJECT = "gateway";
    private static final String BEARER_PREFIX = "Bearer ";

    private final LlmGatewayProperties properties;
    private final RateLimitService rateLimitService;
    private final ProblemJsonWriter problemJsonWriter;

    public LlmGatewayAuthFilter(LlmGatewayProperties properties,
            RateLimitService rateLimitService, ProblemJsonWriter problemJsonWriter) {
        this.properties = properties;
        this.rateLimitService = rateLimitService;
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        SubPath subPath = SubPath.of(request.getRequestURI());
        if (subPath == null) {
            forbidden(request, response);
            return;
        }
        if (!properties.allowedSourceIp().equals(request.getRemoteAddr())) {
            forbidden(request, response);
            return;
        }
        if (!tokenMatches(request)) {
            unauthorized(request, response);
            return;
        }
        try {
            rateLimitService.hit(subPath.rateLimitScope, RATE_LIMIT_SUBJECT,
                    subPath.limitPerMinute(properties));
        } catch (ApiException e) {
            rateLimited(request, response, e);
            return;
        }
        long cap = subPath.bodyCapBytes(properties);
        if (request.getContentLengthLong() > cap) {
            payloadTooLarge(request, response);
            return;
        }
        filterChain.doFilter(new BodyCappingRequest(request, cap), response);
    }

    /**
     * Constant-time bearer comparison against the current token, then the
     * previous one (rotation overlap); fails closed when no current token is
     * configured — a rotation must never run on the previous value alone.
     */
    private boolean tokenMatches(HttpServletRequest request) {
        if (properties.tokenUnset()) {
            return false;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String presented = header.substring(BEARER_PREFIX.length()).trim();
        if (constantTimeEquals(presented, properties.token())) {
            return true;
        }
        return properties.previousTokenSet()
                && constantTimeEquals(presented, properties.previousToken());
    }

    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
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

    private void rateLimited(HttpServletRequest request, HttpServletResponse response,
            ApiException e) throws IOException {
        if (e.getRetryAfterSeconds() != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
        }
        problemJsonWriter.write(request, response, 429,
                ErrorCodes.RATE_LIMITED, e.getTitle(), e.getDetail());
    }

    private void payloadTooLarge(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        problemJsonWriter.write(request, response, 413, ErrorCodes.VALIDATION_FAILED,
                "요청 본문이 너무 큽니다", "요청 본문이 허용 크기를 초과했습니다.");
    }

    /** The three calls this surface serves, each with its own bucket and cap. */
    private enum SubPath {
        SYNC("/internal/llm/sync", SYNC_RATE_LIMIT_SCOPE),
        USAGE("/internal/llm/usage", USAGE_RATE_LIMIT_SCOPE),
        BODIES("/internal/llm/bodies", BODIES_RATE_LIMIT_SCOPE);

        private final String uri;
        private final String rateLimitScope;

        SubPath(String uri, String rateLimitScope) {
            this.uri = uri;
            this.rateLimitScope = rateLimitScope;
        }

        static SubPath of(String requestUri) {
            for (SubPath subPath : values()) {
                if (subPath.uri.equals(requestUri)) {
                    return subPath;
                }
            }
            return null;
        }

        int limitPerMinute(LlmGatewayProperties properties) {
            return switch (this) {
                case SYNC -> properties.syncRateLimitPerMinute();
                case USAGE -> properties.usageRateLimitPerMinute();
                case BODIES -> properties.bodiesRateLimitPerMinute();
            };
        }

        long bodyCapBytes(LlmGatewayProperties properties) {
            return switch (this) {
                case SYNC -> properties.maxSyncBodyBytes();
                case USAGE -> properties.maxUsageBodyBytes();
                case BODIES -> properties.maxBodiesBodyBytes();
            };
        }
    }
}
