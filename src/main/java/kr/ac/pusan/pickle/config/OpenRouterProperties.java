package kr.ac.pusan.pickle.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenRouter key-management link settings ({@code pickle.openrouter.*}). Only
 * the transport belongs here: every management credential is an encrypted
 * per-account row, so there is no key in configuration to fail over to.
 *
 * @param baseUrl        management API base, default the public endpoint
 * @param connectTimeout TCP connect timeout (default 5s)
 * @param readTimeout    per-request read timeout (default 30s)
 */
@ConfigurationProperties(prefix = "pickle.openrouter")
public record OpenRouterProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    public OpenRouterProperties {
        baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://openrouter.ai/api/v1";
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(30);
    }
}
