package kr.ac.pusan.pickle.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.meta.SystemStatusResponse;
import kr.ac.pusan.pickle.meta.SystemStatusService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Maintenance mode (contract v0.9.0, GET /meta/status semantics). When
 * {@code maintenance_mode} is on, requests from anonymous users and from any
 * role below the admin tier are rejected with 503 {@code MAINTENANCE_MODE} so
 * the platform can be worked on while admins keep full access.
 *
 * <p>Registered <b>after</b> {@link JwtAuthenticationFilter} in the security
 * chain (SecurityConfig) so the authenticated principal — hence its role — is
 * available here. The maintenance flag is read through {@link
 * SystemStatusService}'s short cache, so a toggle propagates within its TTL and
 * this hot path costs no per-request DB hit.</p>
 *
 * <p><b>Always-exempt paths</b> are load-bearing: {@code /auth/login|mfa|refresh|
 * logout} (admins must be able to log in during maintenance — the 2FA step-up
 * {@code /auth/mfa} runs with an anonymous principal, so gating it would 503
 * every enrolled admin at stage 2; deploy health also refreshes tokens),
 * {@code /meta/**} (the status poll that surfaces the notice,
 * per contract), the actuator health endpoint (the deploy health gate polls it —
 * a 503 would roll back a good deploy), and the springdoc {@code /openapi} document.
 * The admin tier is matched by role <em>name</em> — ORG_ADMIN/SYS_ADMIN plus the
 * ORG_MANAGER/SYS_MANAGER operator-tier strings — so no change is needed when
 * the manager roles land.</p>
 */
@Component
public class MaintenanceModeFilter extends OncePerRequestFilter {

    /** Roles that keep full access during maintenance (names, forward-compatible). */
    private static final Set<String> ADMIN_TIER_ROLES =
            Set.of("ORG_ADMIN", "SYS_ADMIN", "ORG_MANAGER", "SYS_MANAGER");

    private static final String DEFAULT_MESSAGE =
            "서비스 점검 중입니다. 잠시 후 다시 이용해 주세요.";

    private final SystemStatusService systemStatusService;
    private final ProblemJsonWriter problemJsonWriter;

    public MaintenanceModeFilter(SystemStatusService systemStatusService,
            ProblemJsonWriter problemJsonWriter) {
        this.systemStatusService = systemStatusService;
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (isExempt(request.getRequestURI()) || isAdminTier() || !maintenanceActive()) {
            filterChain.doFilter(request, response);
            return;
        }
        SystemStatusResponse status = systemStatusService.current();
        String detail = status.maintenanceMessage() != null && !status.maintenanceMessage().isBlank()
                ? status.maintenanceMessage()
                : DEFAULT_MESSAGE;
        problemJsonWriter.write(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                ErrorCodes.MAINTENANCE_MODE, "서비스 점검 중", detail);
    }

    private boolean maintenanceActive() {
        return systemStatusService.current().maintenance();
    }

    private boolean isAdminTier() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            return false;
        }
        return ADMIN_TIER_ROLES.contains(user.role().name());
    }

    private static boolean isExempt(String uri) {
        return "/api/v1/auth/login".equals(uri)
                || "/api/v1/auth/mfa".equals(uri)
                || "/api/v1/auth/refresh".equals(uri)
                || "/api/v1/auth/logout".equals(uri)
                || uri.startsWith("/api/v1/meta")
                || uri.startsWith("/api/v1/openapi")
                || uri.startsWith("/actuator/health");
    }
}
