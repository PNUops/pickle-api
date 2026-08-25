package kr.ac.pusan.pickle.oauth;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Talks to Google: builds the authorization URL, exchanges the code, and
 * verifies the returned ID token.
 *
 * <p>The exchange happens server-to-server over TLS, which by OIDC Core §3.1.3.7
 * would allow skipping signature verification. It is verified anyway, because
 * that exemption rests on the token having arrived over that channel — an
 * assumption nothing in the code states, and one a later refactor that accepted
 * an ID token from the browser would silently break. Verifying puts the
 * requirement where it cannot be lost.
 */
@Component
public class GoogleOidcClient {

    private final GoogleOauthProperties properties;
    private final RestClient restClient;

    /** Built lazily: an unconfigured environment must still start. */
    private volatile @Nullable JwtDecoder decoder;

    public GoogleOidcClient(GoogleOauthProperties properties) {
        this.properties = properties;
        // Built here rather than from an injected builder, matching the other
        // outbound clients in this codebase (Proxmox, OpenRouter, proxy agent):
        // there is no application-wide RestClient.Builder bean, and a call to
        // Google should not inherit interceptors added for somebody else.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * The URL the browser is sent to.
     *
     * <p>{@code hd} is included so the account chooser offers the right domain,
     * but it is a <b>hint, not a control</b> — the client can drop or change it.
     * The binding check is on the verified id token in {@link #verify}. Reading
     * this parameter as though it enforced anything is the classic mistake in
     * this flow.
     */
    public String authorizationUrl(String state, String nonce, String codeChallenge, boolean forceLogin) {
        StringBuilder url = new StringBuilder(properties.authorizationUri())
                .append("?response_type=code")
                .append("&client_id=").append(enc(properties.clientId()))
                .append("&redirect_uri=").append(enc(properties.redirectUri()))
                .append("&scope=").append(enc("openid email profile"))
                .append("&state=").append(enc(state))
                .append("&nonce=").append(enc(nonce))
                .append("&code_challenge=").append(enc(codeChallenge))
                .append("&code_challenge_method=S256")
                .append("&hd=").append(enc(properties.hostedDomain()));
        if (forceLogin) {
            // Sudo-mode: without this an existing Google session is accepted
            // silently and the reverification proves nothing.
            url.append("&prompt=login");
        }
        return url.toString();
    }

    /** Exchanges the authorization code and returns the verified ID-token claims. */
    public GoogleIdentity exchange(String code, String codeVerifier, String expectedNonce) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("code_verifier", codeVerifier);

        Map<?, ?> token;
        try {
            token = restClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            throw exchangeFailed("토큰 교환에 실패했습니다.", e);
        }
        if (token == null || !(token.get("id_token") instanceof String idToken)) {
            throw exchangeFailed("구글 응답에 id_token이 없습니다.", null);
        }
        return verify(idToken, expectedNonce);
    }

    /**
     * Signature, then the four claim checks that decide whether this token is
     * ours and fresh, then the two that decide whether the account may sign in.
     */
    GoogleIdentity verify(String idToken, String expectedNonce) {
        org.springframework.security.oauth2.jwt.Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (RuntimeException e) {
            throw exchangeFailed("id_token 검증에 실패했습니다.", e);
        }

        // aud: a correctly signed token issued for a DIFFERENT client is still a
        // valid Google token. Skipping this is how one app accepts another's.
        if (!List.of(properties.clientId()).equals(audienceOf(jwt))) {
            throw exchangeFailed("id_token의 대상이 이 클라이언트가 아닙니다.", null);
        }
        // getClaimAsString, not String.valueOf(getClaim(...)): getClaim is
        // generic, so the compiler infers char[] and picks String.valueOf(char[]),
        // which throws ClassCastException at runtime on a String claim.
        if (!properties.issuers().contains(jwt.getClaimAsString("iss"))) {
            throw exchangeFailed("id_token의 발급자가 구글이 아닙니다.", null);
        }
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || expiresAt.isBefore(Instant.now())) {
            throw exchangeFailed("id_token이 만료되었습니다.", null);
        }
        // nonce binds the token to the authorization request we started, so a
        // token captured from another flow cannot be replayed into this one.
        if (!expectedNonce.equals(jwt.getClaim("nonce"))) {
            throw exchangeFailed("id_token이 이 요청과 연결되지 않았습니다.", null);
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || !Boolean.TRUE.equals(jwt.getClaim("email_verified"))) {
            throw domainNotAllowed();
        }
        String hostedDomain = jwt.getClaimAsString("hd");
        // Both, not either. A Workspace tenant can carry alias domains, so `hd`
        // may be the primary while the address is an alias; and we control what
        // lands in users.email, so the address itself has to be in the domain
        // too. Checking one without the other lets an alias through, or a
        // personal account that happens to hold a @pusan.ac.kr alias.
        if (!properties.hostedDomain().equalsIgnoreCase(hostedDomain)
                || !email.toLowerCase(java.util.Locale.ROOT)
                        .endsWith("@" + properties.hostedDomain().toLowerCase(java.util.Locale.ROOT))) {
            throw domainNotAllowed();
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw exchangeFailed("id_token에 sub가 없습니다.", null);
        }
        return new GoogleIdentity(subject, email, jwt.getClaimAsString("name"), hostedDomain);
    }

    private List<String> audienceOf(org.springframework.security.oauth2.jwt.Jwt jwt) {
        List<String> audience = jwt.getAudience();
        return audience == null ? List.of() : audience;
    }

    private JwtDecoder decoder() {
        JwtDecoder current = decoder;
        if (current == null) {
            synchronized (this) {
                current = decoder;
                if (current == null) {
                    current = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
                    decoder = current;
                }
            }
        }
        return current;
    }

    private static ApiException exchangeFailed(String detail, @Nullable Throwable cause) {
        ApiException failure = new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.AUTH_OAUTH_EXCHANGE_FAILED,
                "구글 로그인에 실패했습니다", detail);
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }

    private static ApiException domainNotAllowed() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_OAUTH_DOMAIN_NOT_ALLOWED,
                "사용할 수 없는 구글 계정입니다",
                "부산대학교 구글 계정(@pusan.ac.kr)으로만 로그인할 수 있습니다.");
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A verified Google account. {@code subject} is the stable join key. */
    public record GoogleIdentity(String subject, String email, @Nullable String name,
            @Nullable String hostedDomain) {
    }
}
