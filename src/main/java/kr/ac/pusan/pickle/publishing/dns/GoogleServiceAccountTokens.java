package kr.ac.pusan.pickle.publishing.dns;

import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth 2.0 access tokens for a Google service account, obtained with the
 * JWT-bearer grant: a self-signed RS256 assertion (issuer = the account's
 * email, audience = its token endpoint, the Cloud DNS scope) exchanged for a
 * bearer token that is cached until shortly before it expires.
 *
 * <p>This is the whole of the vendor SDK the platform needs. The key file is
 * the standard service-account JSON ({@code client_email},
 * {@code private_key} as PKCS#8 PEM, {@code token_uri}); the signing library
 * is the jjwt already used for the platform's own sessions, so no Google
 * artifact enters the dependency tree for one HTTP exchange.</p>
 */
public class GoogleServiceAccountTokens {

    /** Read-write scope for Cloud DNS record sets and changes. */
    static final String SCOPE = "https://www.googleapis.com/auth/ndev.clouddns.readwrite";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(60);
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final String clientEmail;
    private final PrivateKey privateKey;
    private final String tokenUri;
    private final RestClient restClient;

    private volatile Cached cached;

    private record Cached(String token, Instant expiresAt) {
    }

    GoogleServiceAccountTokens(String clientEmail, PrivateKey privateKey, String tokenUri,
            RestClient restClient) {
        this.clientEmail = clientEmail;
        this.privateKey = privateKey;
        this.tokenUri = tokenUri;
        this.restClient = restClient;
    }

    /**
     * Reads a service-account key file. Throws on an unreadable or malformed
     * file so the caller can fall back to the unconfigured provider with the
     * reason in the log; the key material itself is never logged.
     */
    public static GoogleServiceAccountTokens load(Path keyFile, RestClient restClient) {
        JsonNode key;
        try {
            key = JSON.readTree(Files.readString(keyFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DnsProviderException("service-account key file unreadable: " + keyFile
                    + " (" + e.getMessage() + ")", e);
        } catch (RuntimeException e) {
            throw new DnsProviderException("service-account key file is not JSON: " + keyFile, e);
        }
        String email = text(key, "client_email");
        String pem = text(key, "private_key");
        if (email == null || pem == null) {
            throw new DnsProviderException("service-account key file lacks client_email or "
                    + "private_key: " + keyFile);
        }
        String tokenUri = text(key, "token_uri");
        return new GoogleServiceAccountTokens(email, parsePkcs8(pem),
                tokenUri != null ? tokenUri : "https://oauth2.googleapis.com/token", restClient);
    }

    /** The account this signs for (the {@code client_email}). */
    public String clientEmail() {
        return clientEmail;
    }

    /** A bearer token, refreshed when within a minute of expiry. */
    public String accessToken() {
        Cached current = cached;
        Instant now = Instant.now();
        if (current != null && now.isBefore(current.expiresAt().minus(REFRESH_MARGIN))) {
            return current.token();
        }
        synchronized (this) {
            current = cached;
            if (current != null && now.isBefore(current.expiresAt().minus(REFRESH_MARGIN))) {
                return current.token();
            }
            Cached fresh = exchange(now);
            cached = fresh;
            return fresh.token();
        }
    }

    private Cached exchange(Instant now) {
        String assertion = Jwts.builder()
                .issuer(clientEmail)
                .audience().add(tokenUri).and()
                .claim("scope", SCOPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ASSERTION_LIFETIME)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
        String form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
                + "&assertion=" + assertion;
        String body;
        try {
            body = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .exchange((request, response) -> {
                        String text = new String(response.getBody().readAllBytes(),
                                StandardCharsets.UTF_8);
                        int status = response.getStatusCode().value();
                        if (status != 200) {
                            throw new DnsProviderException("Google token endpoint HTTP " + status
                                    + ": " + text);
                        }
                        return text;
                    });
        } catch (ResourceAccessException e) {
            throw new DnsProviderException("Google token endpoint unreachable: "
                    + e.getMessage(), e);
        }
        JsonNode node = JSON.readTree(body);
        String token = text(node, "access_token");
        if (token == null) {
            throw new DnsProviderException("Google token endpoint answered without access_token");
        }
        long expiresIn = node.path("expires_in").isNumber() ? node.path("expires_in").asLong() : 3600;
        return new Cached(token, now.plusSeconds(expiresIn));
    }

    private static PrivateKey parsePkcs8(String pem) {
        // PEM armor markers, not key material. # not-a-secret
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "") // # not-a-secret
                .replace("-----END PRIVATE KEY-----", "") // # not-a-secret
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new DnsProviderException("service-account private_key is not a PKCS#8 RSA key", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
