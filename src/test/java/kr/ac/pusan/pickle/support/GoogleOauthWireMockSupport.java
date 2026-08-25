package kr.ac.pusan.pickle.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * Stands in for Google: a WireMock server serving a JWKS built from a keypair
 * this class generates, and a token endpoint that returns an ID token signed
 * with it.
 *
 * <p>Real signatures rather than a stubbed decoder, because the checks worth
 * testing are the ones on a token that verifies — a wrong {@code aud}, a stale
 * {@code nonce}, an address outside the Workspace domain. A test that skipped
 * verification could not tell those apart from a forgery.
 */
public final class GoogleOauthWireMockSupport implements AutoCloseable {

    public static final String CLIENT_ID = "test-client-id.apps.googleusercontent.com";
    public static final String HOSTED_DOMAIN = "pusan.ac.kr";
    private static final String KEY_ID = "test-key";

    private final WireMockServer server;
    private final RSAKey signingKey;

    private GoogleOauthWireMockSupport(WireMockServer server, RSAKey signingKey) {
        this.server = server;
        this.signingKey = signingKey;
    }

    public static GoogleOauthWireMockSupport start() {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
            WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            server.start();
            server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/jwks"))
                    .willReturn(WireMock.okJson(new JWKSet(key.toPublicJWK()).toString())));
            return new GoogleOauthWireMockSupport(server, key);
        } catch (Exception e) {
            throw new IllegalStateException("could not start the Google stub", e);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    public String tokenUri() {
        return baseUrl() + "/token";
    }

    public String jwkSetUri() {
        return baseUrl() + "/jwks";
    }

    public WireMockServer server() {
        return server;
    }

    public void reset() {
        server.resetAll();
        server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/jwks"))
                .willReturn(WireMock.okJson(new JWKSet(signingKey.toPublicJWK()).toString())));
    }

    /** The token endpoint returns an ID token with these claims, signed for real. */
    public void stubToken(Map<String, Object> claims) {
        String idToken = signedIdToken(claims);
        server.stubFor(WireMock.post(WireMock.urlPathEqualTo("/token"))
                .willReturn(WireMock.okJson("""
                        {"access_token":"stub","token_type":"Bearer","expires_in":3599,"id_token":"%s"}
                        """.formatted(idToken))));
    }

    /** The token endpoint refuses the exchange (bad or replayed code). */
    public void stubTokenFailure() {
        server.stubFor(WireMock.post(WireMock.urlPathEqualTo("/token"))
                .willReturn(WireMock.aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_grant\"}")));
    }

    /**
     * A well-formed set of claims for a university account. Cases override the
     * one field they are about, so a failure names the claim that broke it.
     */
    public static Map<String, Object> claims(String subject, String email, String nonce) {
        return new java.util.LinkedHashMap<>(Map.of(
                "iss", "https://accounts.google.com",
                "aud", CLIENT_ID,
                "sub", subject,
                "email", email,
                "email_verified", true,
                "hd", HOSTED_DOMAIN,
                "name", "테스트사용자",
                "nonce", nonce));
    }

    private String signedIdToken(Map<String, Object> claims) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
            claims.forEach(builder::claim);
            builder.issueTime(Date.from(Instant.now()));
            if (!claims.containsKey("exp")) {
                builder.expirationTime(Date.from(Instant.now().plusSeconds(600)));
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(KEY_ID).type(JOSEObjectType.JWT).build(),
                    builder.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the stub id token", e);
        }
    }

    @Override
    public void close() {
        server.stop();
    }
}
