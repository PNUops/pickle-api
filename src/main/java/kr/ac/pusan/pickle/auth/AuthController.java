package kr.ac.pusan.pickle.auth;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.auth.dto.AuthTokenResponse;
import kr.ac.pusan.pickle.auth.dto.LoginRequest;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.auth.dto.MfaChallengeResponse;
import kr.ac.pusan.pickle.auth.dto.MfaLoginRequest;
import kr.ac.pusan.pickle.auth.dto.PasswordResetConfirmRequest;
import kr.ac.pusan.pickle.auth.dto.PasswordResetRequest;
import kr.ac.pusan.pickle.auth.dto.ResendVerificationRequest;
import kr.ac.pusan.pickle.auth.dto.ReverifyRequest;
import kr.ac.pusan.pickle.auth.dto.ReverifyResponse;
import kr.ac.pusan.pickle.auth.dto.SignupRequest;
import kr.ac.pusan.pickle.auth.dto.VerifyEmailRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code auth} (openapi.yaml v0.2.0, server /api/v1). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionCookies sessionCookies;
    private final ReauthService reauthService;

    public AuthController(AuthService authService, SessionCookies sessionCookies,
            ReauthService reauthService) {
        this.authService = authService;
        this.sessionCookies = sessionCookies;
        this.reauthService = reauthService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse signup(@Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest) {
        return authService.signup(request, clientIp(httpRequest));
    }

    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
            HttpServletRequest httpRequest) {
        return authService.verifyEmail(request.token(), clientIp(httpRequest));
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resendVerification(
            @Valid @RequestBody ResendVerificationRequest request, HttpServletRequest httpRequest) {
        return authService.resendVerification(request.email(), clientIp(httpRequest));
    }

    // The runtime returns one of two bodies from a sealed outcome, which
    // springdoc cannot infer from ResponseEntity<Object> — declare the union.
    @ApiResponse(responseCode = "200", description = "로그인 성공(토큰 발급) 또는 2FA 챌린지",
            content = @Content(schema = @Schema(
                    oneOf = {AuthTokenResponse.class, MfaChallengeResponse.class})))
    @PostMapping("/login")
    public ResponseEntity<Object> login(@Valid @RequestBody LoginRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        AuthService.LoginOutcome outcome = authService.login(request, clientIp(httpRequest), userAgent);
        // Enrolled account: return the step-up challenge, no cookies.
        if (outcome instanceof AuthService.MfaChallenge challenge) {
            return ResponseEntity.ok(MfaChallengeResponse.of(challenge.mfaToken()));
        }
        ResponseEntity<AuthTokenResponse> tokens = withRefreshCookie((AuthService.AuthResult) outcome);
        return ResponseEntity.status(tokens.getStatusCode()).headers(tokens.getHeaders())
                .body((Object) tokens.getBody());
    }

    @PostMapping("/mfa")
    public ResponseEntity<AuthTokenResponse> completeMfa(@Valid @RequestBody MfaLoginRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.completeMfaLogin(request.mfaToken(), request.code(),
                request.recoveryCode(), clientIp(httpRequest), userAgent);
        return withRefreshCookie(result);
    }

    @Parameter(name = "X-Pickle-Csrf", in = ParameterIn.HEADER, required = true,
            schema = @Schema(type = "string"),
            description = "CSRF 이중 제출 토큰 — __Host-pickle_csrf 쿠키 값과 일치해야 합니다 (필터 강제)")
    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(
            @CookieValue(value = SessionCookies.REFRESH_COOKIE, required = false) String refreshToken,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.refresh(refreshToken, clientIp(httpRequest), userAgent);
        return withRefreshCookie(result);
    }

    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request, HttpServletRequest httpRequest) {
        return authService.requestPasswordReset(request.email(), clientIp(httpRequest));
    }

    @PostMapping("/password-reset/confirm")
    public MessageResponse confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest request, HttpServletRequest httpRequest) {
        return authService.confirmPasswordReset(request.token(), request.newPassword(),
                clientIp(httpRequest));
    }

    /** Sudo-mode issue (v0.24.0) — the raw token is bearer-equivalent; never cached. */
    @PostMapping("/reverify")
    public ResponseEntity<ReverifyResponse> reverify(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            kr.ac.pusan.pickle.security.AuthenticatedUser principal,
            @Valid @RequestBody ReverifyRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(reauthService.issue(principal, request.password(), clientIp(httpRequest)));
    }

    @Parameter(name = "X-Pickle-Csrf", in = ParameterIn.HEADER, required = true,
            schema = @Schema(type = "string"),
            description = "CSRF 이중 제출 토큰 — __Host-pickle_csrf 쿠키 값과 일치해야 합니다 (필터 강제)")
    @PostMapping("/logout")
    // ResponseEntity carries the cookie-clearing Set-Cookie headers; the
    // annotation is what makes the 204 visible in the generated contract.
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> logout(
            @CookieValue(value = SessionCookies.REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        authService.logout(refreshToken, clientIp(httpRequest));
        ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
        sessionCookies.cleared().forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
        return response.build();
    }

    private ResponseEntity<AuthTokenResponse> withRefreshCookie(AuthService.AuthResult result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        sessionCookies.issued(result.refreshToken())
                .forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
        return response.body(result.body());
    }
}
