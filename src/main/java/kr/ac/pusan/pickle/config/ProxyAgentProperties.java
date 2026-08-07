package kr.ac.pusan.pickle.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * proxy-agent reverse-proxy control link settings ({@code pickle.proxy-agent.*},
 * the proxy-agent control contract). pickle-api is the client; a JobRunr job pushes
 * desired routing state to the Go agent on LXC 100.
 *
 * <p>The token has no default outside dev/test: when blank the client fails
 * closed at first use (never sends an empty bearer), mirroring the Proxmox and
 * sshgw token handling.</p>
 *
 * @param baseUrl        agent base URL, e.g. {@code http://172.30.1.10:9443}
 *                       (the agent's own listener — NOT nginx :80; tests point
 *                       it at WireMock)
 * @param token          shared bearer token (PICKLE_PROXY_AGENT_TOKEN)
 * @param connectTimeout TCP connect timeout (default 5s)
 * @param readTimeout    per-request read timeout (default 190s: it must outlast
 *                       the agent's own 180s subprocess bound — certificate
 *                       issuance runs inline in {@code /apply} — or the two
 *                       sides disagree about whether the work happened)
 */
@ConfigurationProperties(prefix = "pickle.proxy-agent")
public record ProxyAgentProperties(
        String baseUrl,
        String token,
        Duration connectTimeout,
        Duration readTimeout) {

    public ProxyAgentProperties {
        baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "http://172.30.1.10:9443";
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(190);
    }
}
