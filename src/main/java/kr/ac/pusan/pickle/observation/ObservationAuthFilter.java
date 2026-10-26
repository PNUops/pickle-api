package kr.ac.pusan.pickle.observation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.common.web.BodyCappingRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/** A direct socket peer and a dedicated bearer authorize only sample ingestion. */
public final class ObservationAuthFilter extends OncePerRequestFilter {
    private final ObservationProperties properties;
    private final RateLimitService rate;
    private final ProblemJsonWriter writer;
    private final Environment environment;
    public ObservationAuthFilter(ObservationProperties properties, RateLimitService rate,
            ProblemJsonWriter writer, Environment environment) {
        this.properties = properties; this.rate = rate; this.writer = writer; this.environment = environment;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || !"/internal/inference-observations".equals(request.getRequestURI())
                || !properties.configured()
                || !"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none"))) {
            deny(request, response, 403); return;
        }
        HttpServletRequest socket = request;
        while (socket instanceof HttpServletRequestWrapper wrapper
                && wrapper.getRequest() instanceof HttpServletRequest original) { socket = original; }
        if (!properties.allowedSourceIp().equals(socket.getRemoteAddr())) { deny(request, response, 403); return; }
        String header = request.getHeader("Authorization");
        String presented = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        if (!equal(presented, properties.token()) && !equal(presented, properties.previousToken())) {
            deny(request, response, 401); return;
        }
        try { rate.hit("inference_observation", properties.sourceId().toString(), ObservationLimits.RATE_PER_MINUTE); }
        catch (ApiException e) {
            if (e.getRetryAfterSeconds() != null) response.setHeader("Retry-After", e.getRetryAfterSeconds().toString());
            writer.write(request, response, 429, ErrorCodes.RATE_LIMITED, e.getTitle(), e.getDetail()); return;
        }
        if (request.getContentLengthLong() > ObservationLimits.MAX_BODY_BYTES) { deny(request, response, 413); return; }
        chain.doFilter(new BodyCappingRequest(request, ObservationLimits.MAX_BODY_BYTES), response);
    }
    private static boolean equal(String value, @Nullable String expected) {
        return expected != null && !expected.isBlank() && MessageDigest.isEqual(
                value.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
    private void deny(HttpServletRequest request, HttpServletResponse response, int status) throws IOException {
        if (status == 413) {
            writer.write(request, response, 413, ErrorCodes.VALIDATION_FAILED,
                    "요청 본문이 너무 큽니다", "요청 본문이 허용 크기를 초과했습니다.");
            return;
        }
        writer.write(request, response, status, status == 401 ? ErrorCodes.AUTH_TOKEN_INVALID : ErrorCodes.ACCESS_DENIED,
                "관측 보고를 처리할 수 없습니다", "허용된 보고 설정과 요청을 확인해 주세요.");
    }
}
