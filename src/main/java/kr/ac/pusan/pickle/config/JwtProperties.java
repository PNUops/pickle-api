package kr.ac.pusan.pickle.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT signing settings ({@code pickle.jwt.*}; secret from PICKLE_JWT_SECRET). */
@ConfigurationProperties(prefix = "pickle.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl) {
}
