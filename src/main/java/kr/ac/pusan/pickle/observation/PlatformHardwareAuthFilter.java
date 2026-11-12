package kr.ac.pusan.pickle.observation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import kr.ac.pusan.pickle.auth.RateLimitService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.common.web.BodyCappingRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/** A dedicated bearer and unforwarded TCP peer admit only the bounded hardware POST. */
public final class PlatformHardwareAuthFilter extends OncePerRequestFilter {
    private final PlatformHardwareProperties properties;
    private final RateLimitService rate;
    private final ProblemJsonWriter writer;
    private final Environment environment;
    public PlatformHardwareAuthFilter(PlatformHardwareProperties properties, RateLimitService rate,
            ProblemJsonWriter writer, Environment environment) {
        this.properties = properties; this.rate = rate; this.writer = writer; this.environment = environment;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())
                || !"/internal/platform-hardware-observations".equals(request.getRequestURI())
                || !properties.configured()
                || !"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none"))
                || Collections.list(request.getHeaderNames()).stream().map(name -> name.toLowerCase(Locale.ROOT))
                        .anyMatch(name -> name.equals("forwarded") || name.equals("x-real-ip") || name.startsWith("x-forwarded-"))) {
            deny(request, response, 403); return;
        }
        HttpServletRequest socket = request;
        while (socket instanceof HttpServletRequestWrapper wrapper
                && wrapper.getRequest() instanceof HttpServletRequest original) { socket = original; }
        if (!properties.allowedSourceIp().equals(socket.getRemoteAddr())) { deny(request, response, 403); return; }
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (!equal(token, properties.token()) && !equal(token, properties.previousToken())) {
            deny(request, response, 401); return;
        }
        try { rate.hit("platform_hardware_observation", properties.sourceId().toString(), 120); }
        catch (ApiException exception) {
            if (exception.getRetryAfterSeconds() != null) response.setHeader("Retry-After", exception.getRetryAfterSeconds().toString());
            writer.write(request, response, 429, ErrorCodes.RATE_LIMITED, exception.getTitle(), exception.getDetail()); return;
        }
        if (request.getContentLengthLong() > PlatformHardwareValidator.MAX_BODY_BYTES) { deny(request, response, 413); return; }
        chain.doFilter(new BodyCappingRequest(request, PlatformHardwareValidator.MAX_BODY_BYTES), response);
    }
    private static boolean equal(String value, @Nullable String expected) {
        return expected != null && !expected.isBlank() && MessageDigest.isEqual(
                value.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
    private void deny(HttpServletRequest request, HttpServletResponse response, int status) throws IOException {
        writer.write(request, response, status,
                status == 413 ? ErrorCodes.VALIDATION_FAILED : status == 401 ? ErrorCodes.AUTH_TOKEN_INVALID : ErrorCodes.ACCESS_DENIED,
                "장비 관측 보고를 처리할 수 없어요", "허용된 보고 설정과 본문 크기를 확인해 주세요.");
    }
}
