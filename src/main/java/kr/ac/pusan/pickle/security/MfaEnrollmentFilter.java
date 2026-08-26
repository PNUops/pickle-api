package kr.ac.pusan.pickle.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import kr.ac.pusan.pickle.config.MfaProperties;
import kr.ac.pusan.pickle.mfa.MfaService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * When admin 2FA enforcement is on (the default in production), a <b>sys-tier</b>
 * account that has not enrolled in 2FA is a <b>scope restriction, not a login
 * block</b> — every endpoint returns 403 {@code MFA_ENROLLMENT_REQUIRED}
 * <i>except</i> the enrollment/auth/profile/meta surfaces it needs to actually
 * enroll. Runs right after {@link JwtAuthenticationFilter} so the principal is
 * already resolved.
 *
 * <p>The org tier is deliberately outside it (operator decision, 2026-08-25).
 * Enforcement was switched off for all four admin roles because the
 * organisations' administrators could not use TOTP, which left the two roles
 * that hold every destructive operation unprotected as well. Splitting the
 * requirement by tier is what lets it come back on for those: the org tier is
 * asked in the console rather than blocked here.
 */
@Component
public class MfaEnrollmentFilter extends OncePerRequestFilter {

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
                    && principal.role().isSysTier()
                    && !mfaService.isEnrolled(principal.id())) {
                problemJsonWriter.write(request, response, HttpStatus.FORBIDDEN.value(),
                        ErrorCodes.MFA_ENROLLMENT_REQUIRED, "2단계 인증 등록이 필요합니다",
                        "관리자 계정은 2단계 인증 등록 후 이용할 수 있습니다. 계정 설정에서 2단계 인증을 등록해 주세요.");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Endpoints an unenrolled admin must still reach to enroll (and read their
     * own profile).
     *
     * <p>{@code POST /me/password} is on the list because enrolment needs a
     * password and an account made through Google has none. Without the
     * exemption that account cannot enrol (no password to give
     * {@code MfaService.begin}) and cannot obtain one (this filter refuses the
     * endpoint that would give it), which is a permanent lock-out reachable by
     * simply promoting a Google account to an admin role. The exemption grants
     * nothing else: the endpoint still demands a reauthentication token, and
     * the filter keeps refusing every other surface until 2FA is on.
     */
    private static boolean isExempt(String uri) {
        return "/api/v1/me".equals(uri)
                || "/api/v1/me/password".equals(uri)
                || uri.startsWith("/api/v1/me/mfa/")
                || uri.startsWith("/api/v1/auth/")
                || uri.startsWith("/api/v1/meta/")
                || uri.startsWith("/api/v1/openapi")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/actuator/");
    }
}
