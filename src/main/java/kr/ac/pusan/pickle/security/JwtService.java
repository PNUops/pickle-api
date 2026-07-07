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
 * {@code sub}, {@code role}, {@code org_id}, {@code token_version}.
 */
@Component
public class JwtService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_ORG_ID = "org_id";
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
        var builder = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())));
        if (user.getOrgId() != null) {
            builder.claim(CLAIM_ORG_ID, user.getOrgId());
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    /** @throws io.jsonwebtoken.JwtException when invalid/expired */
    public Claims parse(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }
}
