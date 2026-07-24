package kr.ac.pusan.pickle.auth;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

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
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code auth} (openapi.yaml v0.2.0, server /api/v1). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionCookies sessionCookies;

    public AuthController(AuthService authService, SessionCookies sessionCookies) {
        this.authService = authService;
        this.sessionCookies = sessionCookies;
    }

    @PostMapping("/signup")
    public ResponseEntity<MessageResponse> signup(@Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(authService.signup(request, clientIp(httpRequest)));
    }

    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
            HttpServletRequest httpRequest) {
        return authService.verifyEmail(request.token(), clientIp(httpRequest));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(authService.resendVerification(request.email(), clientIp(httpRequest)));
    }

    // The runtime returns one of two bodies from a sealed outcome, which
    // springdoc cannot infer from ResponseEntity<Object> — declare the union.
    @ApiResponse(responseCode = "200", description = "로그인 성공(토큰 발급) 또는 2FA 챌린지",
            content = @Content(schema = @Schema(
                    oneOf = {AuthTokenResponse.class, MfaChallengeResponse.class})))
    @PostMapping("/login")
    public ResponseEntity<Object> login(@Valid @RequestBody LoginRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
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
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.completeMfaLogin(request.mfaToken(), request.code(),
                request.recoveryCode(), clientIp(httpRequest), userAgent);
        return withRefreshCookie(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(
            @CookieValue(value = SessionCookies.REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.refresh(refreshToken, clientIp(httpRequest), userAgent);
        return withRefreshCookie(result);
    }

    @PostMapping("/password-reset")
    public ResponseEntity<MessageResponse> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(authService.requestPasswordReset(request.email(), clientIp(httpRequest)));
    }

    @PostMapping("/password-reset/confirm")
    public MessageResponse confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest request, HttpServletRequest httpRequest) {
        return authService.confirmPasswordReset(request.token(), request.newPassword(),
                clientIp(httpRequest));
    }

    @PostMapping("/logout")
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
