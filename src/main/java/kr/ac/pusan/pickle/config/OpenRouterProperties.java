package kr.ac.pusan.pickle.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenRouter key-management link settings ({@code pickle.openrouter.*}). The
 * env management key is retained only as the legacy transition source; new
 * account-bound keys use the encrypted per-account credential keyring.
 *
 * <p>The management key has no default anywhere: when blank the client fails
 * closed at first use (never sends an empty bearer), and the provisioning
 * sweep simply reports every funded key as unconnected — the token-axis
 * service is unaffected, which is the operator-decided failure shape.</p>
 *
 * @param baseUrl        management API base, default the public endpoint
 * @param managementKey  management key (PICKLE_OPENROUTER_MGMT_KEY)
 * @param connectTimeout TCP connect timeout (default 5s)
 * @param readTimeout    per-request read timeout (default 30s)
 * @param accountBindingEnabled whether positive-credit grants may create the
 *                              first immutable account binding
 */
@ConfigurationProperties(prefix = "pickle.openrouter")
public record OpenRouterProperties(
        String baseUrl,
        String managementKey,
        Duration connectTimeout,
        Duration readTimeout,
        boolean accountBindingEnabled) {

    public OpenRouterProperties {
        baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://openrouter.ai/api/v1";
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(30);
    }

    public boolean configured() {
        return managementKey != null && !managementKey.isBlank();
    }
}
