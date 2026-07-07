package kr.ac.pusan.pickle.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Auth flow tunables ({@code pickle.auth.*}). */
@ConfigurationProperties(prefix = "pickle.auth")
public record AuthProperties(
        Duration refreshTokenTtl,
        Duration verificationTokenTtl,
        String verificationBaseUrl) {
}
