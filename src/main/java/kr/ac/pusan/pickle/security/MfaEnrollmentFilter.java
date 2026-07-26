package kr.ac.pusan.pickle.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.config.MfaProperties;
import kr.ac.pusan.pickle.mfa.MfaService;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * When admin 2FA enforcement is on (the default in production), an admin-tier
 * account that has not enrolled in 2FA is a <b>scope restriction, not a login
 * block</b> — every endpoint returns 403 {@code MFA_ENROLLMENT_REQUIRED}
 * <i>except</i> the enrollment/auth/profile/meta surfaces it needs to actually
 * enroll. Runs right after {@link JwtAuthenticationFilter} so the principal is
 * already resolved.
 */
@Component
public class MfaEnrollmentFilter extends OncePerRequestFilter {

    private static final Set<UserRole> ADMIN_TIER = Set.of(UserRole.ORG_MANAGER,
            UserRole.ORG_ADMIN, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN);

    private final MfaProperties mfaProperties;
    private final MfaService mfaService;
    private final ProblemJsonWriter problemJsonWriter;

    public MfaEnrollmentFilter(MfaProperties mfaProperties, MfaService mfaService,
            ProblemJsonWriter problemJsonWriter) {
        this.mfaProperties = mfaProperties;
        this.mfaService = mfaService;
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (mfaProperties.enforceAdmin() && !isExempt(request.getRequestURI())) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser principal
                    && ADMIN_TIER.contains(principal.role())
                    && !mfaService.isEnrolled(principal.id())) {
                problemJsonWriter.write(request, response, HttpStatus.FORBIDDEN.value(),
                        ErrorCodes.MFA_ENROLLMENT_REQUIRED, "2단계 인증 등록이 필요합니다",
                        "관리자 계정은 2단계 인증 등록 후 이용할 수 있습니다. 계정 설정에서 2단계 인증을 등록해 주세요.");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Endpoints an unenrolled admin must still reach to enroll (and read their own profile). */
    private static boolean isExempt(String uri) {
        return "/api/v1/me".equals(uri)
                || uri.startsWith("/api/v1/me/mfa/")
                || uri.startsWith("/api/v1/auth/")
                || uri.startsWith("/api/v1/meta/")
                || uri.startsWith("/api/v1/openapi")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/actuator/");
    }
}
