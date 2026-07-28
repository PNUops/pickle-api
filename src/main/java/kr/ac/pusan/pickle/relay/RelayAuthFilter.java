package kr.ac.pusan.pickle.relay;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.config.RelayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sole access gate for the relay sync surface ({@code /internal/relays/**}).
 * Deliberately NOT the sshgw internal filter: that chain is one static token,
 * one pinned source — reusing it would hand a compromised relay the sshgw
 * token and its slug→VM oracle. Checks, in order:
 *
 * <ol>
 *   <li><b>Path id</b> — the relay id comes from the path; an unparsable path
 *       is refused. Token↔id binding below guarantees a relay only ever
 *       reaches its own row.</li>
 *   <li><b>Relay row</b> — unknown or disabled relays answer a generic 403
 *       (indistinguishable from a source failure).</li>
 *   <li><b>Source pin</b> — the TCP peer ({@code getRemoteAddr()}, never a
 *       spoofable X-Forwarded-For; the sync endpoint is called directly on
 *       :8080) must equal the row's own {@code source_ip}.</li>
 *   <li><b>Per-relay token</b> — constant-time comparison of the presented
 *       bearer's sha256 against the row's OWN hash, so relay A's token can
 *       never resolve relay B's path. A null hash (token not issued yet)
 *       <b>fails closed</b> with 401.</li>
 *   <li><b>Per-relay rate limit</b> — own scope and subject; never the shared
 *       sshgw global bucket (a runaway relay must not 429 SSH logins).</li>
 *   <li><b>Body cap</b> — a declared Content-Length over the cap answers 413,
 *       and the input stream is wrapped so a chunked body is hard-capped at
 *       the same byte count.</li>
 * </ol>
 */
public class RelayAuthFilter extends OncePerRequestFilter {

    static final String RATE_LIMIT_SCOPE = "relay_sync";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern PATH_ID = Pattern.compile("^/internal/relays/(\\d{1,18})(/.*)?$");

    private final RelayRepository relayRepository;
    private final RelayProperties properties;
    private final RateLimitService rateLimitService;
    private final ProblemJsonWriter problemJsonWriter;

    public RelayAuthFilter(RelayRepository relayRepository, RelayProperties properties,
            RateLimitService rateLimitService, ProblemJsonWriter problemJsonWriter) {
        this.relayRepository = relayRepository;
        this.properties = properties;
        this.rateLimitService = rateLimitService;
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Matcher matcher = PATH_ID.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            forbidden(request, response);
            return;
        }
        long relayId = Long.parseLong(matcher.group(1));
        Relay relay = relayRepository.findById(relayId).orElse(null);
        if (relay == null || !relay.isEnabled()) {
            forbidden(request, response);
            return;
        }
        if (!relay.getSourceIp().equals(request.getRemoteAddr())) {
            forbidden(request, response);
            return;
        }
        if (!tokenMatches(request, relay)) {
            unauthorized(request, response);
            return;
        }
        try {
            rateLimitService.hit(RATE_LIMIT_SCOPE, "relay:" + relayId,
                    properties.syncRateLimitPerMinute());
        } catch (ApiException e) {
            rateLimited(request, response, e);
            return;
        }
        long cap = properties.maxSyncBodyBytes();
        if (request.getContentLengthLong() > cap) {
            payloadTooLarge(request, response);
            return;
        }
        filterChain.doFilter(new BodyCappingRequest(request, cap), response);
    }

    /**
     * Constant-time bearer-vs-row-hash comparison; a relay row without an
     * issued token (null hash) fails closed.
     */
    private static boolean tokenMatches(HttpServletRequest request, Relay relay) {
        String storedHash = relay.getTokenHash();
        if (storedHash == null || storedHash.isBlank()) {
            return false;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String presented = header.substring(BEARER_PREFIX.length()).trim();
        return MessageDigest.isEqual(
                RelayTokens.sha256Hex(presented).getBytes(StandardCharsets.UTF_8),
                storedHash.strip().getBytes(StandardCharsets.UTF_8));
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
                "요청 본문이 너무 큽니다", "동기화 요청 본문이 허용 크기를 초과했습니다.");
    }

    /**
     * Hard byte cap on the request body. The Content-Length pre-check catches
     * declared sizes; this wrapper is what bounds a chunked (undeclared-length)
     * body — exceeding the cap aborts the read with an IOException, which the
     * message-conversion layer surfaces as a request error, never an OOM.
     */
    private static final class BodyCappingRequest extends HttpServletRequestWrapper {

        private final long cap;

        BodyCappingRequest(HttpServletRequest request, long cap) {
            super(request);
            this.cap = cap;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                private void count(long n) throws IOException {
                    if (n > 0) {
                        read += n;
                        if (read > cap) {
                            throw new IOException("relay sync body exceeds the configured cap");
                        }
                    }
                }

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    count(b >= 0 ? 1 : 0);
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = delegate.read(b, off, len);
                    count(n);
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    delegate.setReadListener(readListener);
                }
            };
        }
    }
}
