package kr.ac.pusan.pickle.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.ac.pusan.pickle.auth.ReauthService;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.ProblemJsonWriter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Sudo-mode gate (contract v0.24.0): a handler annotated {@link RequireReauth}
 * passes only with a valid {@code X-Reauth-Token}. Runs after the security
 * filter chain, so authentication (401) always precedes the 403 here; role
 * gates ({@code @PreAuthorize}) fire later in method security — the reauth
 * check deliberately does not replace or reorder them. CORS preflights carry
 * no handler annotation and OPTIONS is skipped outright.
 */
@Component
public class ReauthInterceptor implements HandlerInterceptor {

    public static final String REAUTH_HEADER = "X-Reauth-Token";

    private final ReauthService reauthService;
    private final ProblemJsonWriter problemJsonWriter;

    public ReauthInterceptor(ReauthService reauthService, ProblemJsonWriter problemJsonWriter) {
        this.reauthService = reauthService;
        this.problemJsonWriter = problemJsonWriter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        boolean required = AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), RequireReauth.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), RequireReauth.class);
        if (!required) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            // Unauthenticated requests never reach here (the chain 401s first);
            // defensive fall-through keeps the failure mode a clean 403.
            problemJsonWriter.write(request, response, HttpStatus.FORBIDDEN.value(),
                    ErrorCodes.REAUTH_REQUIRED, "재인증이 필요합니다",
                    "민감한 작업입니다. 비밀번호를 다시 확인해 주세요.");
            return false;
        }
        if (!reauthService.isValid(principal.id(), request.getHeader(REAUTH_HEADER))) {
            problemJsonWriter.write(request, response, HttpStatus.FORBIDDEN.value(),
                    ErrorCodes.REAUTH_REQUIRED, "재인증이 필요합니다",
                    "민감한 작업입니다. 비밀번호를 다시 확인해 주세요. (10분간 유효)");
            return false;
        }
        return true;
    }
}
