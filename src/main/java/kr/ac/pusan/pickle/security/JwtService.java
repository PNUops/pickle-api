package kr.ac.pusan.pickle.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import kr.ac.pusan.pickle.config.JwtProperties;
import kr.ac.pusan.pickle.user.User;
import org.springframework.stereotype.Component;

/**
 * Access token (JWT HS256, 15 min). Claims per contract securityScheme:
 * {@code sub}, {@code role}, {@code token_version}.
 *
 * <p>{@code sub} is the account's public identifier. The token is readable by
 * anyone holding it, so the internal key has no business in it — and neither
 * did the organisation's, which is why {@code org_id} was dropped: it was
 * written and never read, and it disclosed the org's sequential id to every
 * client that decoded a token.
 */
@Component
public class JwtService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TOKEN_VERSION = "token_version";

    private final SecretKey key;
    private final JwtParser parser;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException(
                    "pickle.jwt.secret is not set. Provide PICKLE_JWT_SECRET (>= 32 bytes) via /etc/pickle/api.env.");
        }
        byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("pickle.jwt.secret must be at least 32 bytes for HS256.");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.parser = Jwts.parser().verifyWith(key).build();
        this.properties = properties;
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getPublicId().toString())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    /** @throws io.jsonwebtoken.JwtException when invalid/expired */
    public Claims parse(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }
}
