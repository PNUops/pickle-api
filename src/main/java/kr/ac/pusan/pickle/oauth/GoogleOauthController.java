package kr.ac.pusan.pickle.oauth;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.auth.AuthService;
import kr.ac.pusan.pickle.auth.SessionCookies;
import kr.ac.pusan.pickle.auth.dto.AuthTokenResponse;
import kr.ac.pusan.pickle.auth.dto.MfaChallengeResponse;
import kr.ac.pusan.pickle.auth.dto.ReverifyResponse;
import kr.ac.pusan.pickle.oauth.dto.OauthCallbackRequest;
import kr.ac.pusan.pickle.oauth.dto.OauthCompleteRequest;
import kr.ac.pusan.pickle.oauth.dto.OauthLinkedResponse;
import kr.ac.pusan.pickle.oauth.dto.OauthRegistrationResponse;
import kr.ac.pusan.pickle.oauth.dto.OauthStartRequest;
import kr.ac.pusan.pickle.oauth.dto.OauthStartResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code auth}: Google sign-in.
 *
 * <p>Three calls, and no redirect among them. The browser leaves for Google from
 * the URL {@code start} returns, comes back to a console page, and the console
 * posts the code here from its own origin. That keeps the session cookies on an
 * ordinary same-site response — the same one {@code /auth/login} produces —
 * instead of one attached to a cross-site navigation, and keeps the access token
 * out of a URL.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth/google")
public class GoogleOauthController {

    private final GoogleOauthService googleOauthService;
    private final SessionCookies sessionCookies;

    public GoogleOauthController(GoogleOauthService googleOauthService, SessionCookies sessionCookies) {
        this.googleOauthService = googleOauthService;
        this.sessionCookies = sessionCookies;
    }

    /**
     * Begins a round trip. Anonymous for a sign-in; a bearer token is required
     * (and bound into the flow) for the purposes that act on a live session.
     * Accepts no e-mail address of any kind.
     */
    @ApiResponse(responseCode = "200", description = "인가 URL 발급")
    @PostMapping("/start")
    public OauthStartResponse startGoogleOauth(@Valid @RequestBody OauthStartRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal @Nullable AuthenticatedUser principal,
            HttpServletRequest httpRequest) {
        return googleOauthService.start(request, principal, clientIp(httpRequest));
    }

    /**
     * Redeems the authorization code. Five outcomes across the three purposes
     * the flow can carry -- sign-in alone accounts for three of them -- and
     * every one is listed below: a shape the service can return but the
     * {@code oneOf} omits is a response no generated client can represent.
     * {@code LINKED} was exactly that until v0.45.0.
     *
     * <p>Two of the five carry a {@code kind} discriminator and three do not.
     * {@code AuthTokenResponse}, {@code MfaChallengeResponse} and
     * {@code ReverifyResponse} all predate this endpoint and are reached
     * through their own routes as well, so the console narrows those by field
     * probing. Adding {@code kind} to them would change three published
     * schemas to tidy one client branch.
     */
    @ApiResponse(responseCode = "200",
            description = "토큰 발급 / 2FA 챌린지 / 가입 필요 / 재인증 토큰 / 연동 완료",
            content = @Content(schema = @Schema(oneOf = {AuthTokenResponse.class,
                    MfaChallengeResponse.class, OauthRegistrationResponse.class,
                    ReverifyResponse.class, OauthLinkedResponse.class})))
    @PostMapping("/callback")
    public ResponseEntity<Object> completeGoogleOauthCallback(
            @Valid @RequestBody OauthCallbackRequest request,
            @Parameter(hidden = true) @org.springframework.web.bind.annotation.RequestHeader(
                    value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        Object outcome = googleOauthService.callback(request, clientIp(httpRequest), userAgent);
        if (outcome instanceof AuthService.MfaChallenge challenge) {
            return ResponseEntity.ok(MfaChallengeResponse.of(challenge.mfaToken()));
        }
        if (outcome instanceof AuthService.AuthResult result) {
            return withRefreshCookie(result);
        }
        return ResponseEntity.ok(outcome);
    }

    /**
     * Creates the account for a verified Google identity that had none: the
     * onboarding form's submit. Consents travel with it so the account and its
     * consent rows are written in one transaction, as they are at signup.
     */
    // ResponseEntity<Object> gives springdoc nothing to infer from, so without
    // this the generated 200 is an empty object and the console's generated type
    // is empty with it -- the contract and what the client receives come apart.
    // Same reason the callback beside it declares its union explicitly.
    @ApiResponse(responseCode = "200", description = "계정 생성과 토큰 발급",
            content = @Content(schema = @Schema(implementation = AuthTokenResponse.class)))
    @PostMapping("/complete")
    public ResponseEntity<Object> completeGoogleOauthRegistration(
            @Valid @RequestBody OauthCompleteRequest request,
            @Parameter(hidden = true) @org.springframework.web.bind.annotation.RequestHeader(
                    value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        return withRefreshCookie(
                googleOauthService.complete(request, clientIp(httpRequest), userAgent));
    }

    private ResponseEntity<Object> withRefreshCookie(AuthService.AuthResult result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        sessionCookies.issued(result.refreshToken())
                .forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
        return response.body(result.body());
    }
}
