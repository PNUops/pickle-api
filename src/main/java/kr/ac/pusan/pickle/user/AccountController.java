package kr.ac.pusan.pickle.user;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.auth.AuthService;
import kr.ac.pusan.pickle.auth.SessionCookies;
import kr.ac.pusan.pickle.auth.dto.AuthTokenResponse;
import kr.ac.pusan.pickle.auth.dto.MessageResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
import kr.ac.pusan.pickle.user.dto.ChangePasswordRequest;
import kr.ac.pusan.pickle.user.dto.SetPasswordRequest;
import kr.ac.pusan.pickle.user.dto.WithdrawRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code me}: self-service account lifecycle (password / withdraw). */
@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

    private final AuthService authService;
    private final AccountService accountService;
    private final SessionCookies sessionCookies;

    public AccountController(AuthService authService, AccountService accountService,
            SessionCookies sessionCookies) {
        this.authService = authService;
        this.accountService = accountService;
        this.sessionCookies = sessionCookies;
    }

    @PutMapping("/password")
    public ResponseEntity<AuthTokenResponse> changePassword(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangePasswordRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.changePassword(principal.id(),
                request.currentPassword(), request.newPassword(), clientIp(httpRequest), userAgent);
        // Fresh token pair keeps the current session alive; other sessions are invalidated.
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        sessionCookies.issued(result.refreshToken())
                .forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
        return response.body(result.body());
    }

    /**
     * Sets a password on an account that has none, without asking for a
     * current one. {@code @RequireReauth} is what stands in its place, and it
     * is the only thing that does: an account created through Google reaches
     * this by re-verifying with Google, and one that lost its password does
     * not reach it at all (409 — the reset mail is that account's path).
     *
     * <p>Before v0.46.0 the only way to put a password on a Google account was
     * to send the reset mail to someone who was already signed in and ask them
     * to come back through it.
     */
    @PostMapping("/password")
    @RequireReauth
    public ResponseEntity<AuthTokenResponse> setPassword(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SetPasswordRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.setPassword(principal.id(), request.newPassword(),
                clientIp(httpRequest), userAgent);
        // Same as a change: this session keeps going on the fresh pair, every
        // other one is already dead.
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        sessionCookies.issued(result.refreshToken())
                .forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
        return response.body(result.body());
    }

    @PostMapping("/withdraw")
    public ResponseEntity<MessageResponse> withdraw(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody WithdrawRequest request, HttpServletRequest httpRequest) {
        MessageResponse body = accountService.withdraw(principal.id(), request.password(),
                request.totpCode(), request.recoveryCode(), clientIp(httpRequest));
        // Session ends: clear both cookies like logout.
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        sessionCookies.cleared().forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
        return response.body(body);
    }
}
