package kr.ac.pusan.pickle.proxmox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Proxmox VE API client settings ({@code pickle.proxmox.*}; token from
 * PICKLE_PROXMOX_TOKEN_ID / PICKLE_PROXMOX_TOKEN_SECRET, optional CA pin from
 * PICKLE_PROXMOX_CA_CERT). The token may stay unset in dev/test profiles:
 * unlike the JWT secret there is no startup fail-fast — {@link ProxmoxClient}
 * raises a clear error on first use instead, so profiles that never talk to
 * Proxmox can boot without it.
 *
 * @param tokenId          API token id, e.g. {@code pickle@pve!pickle-api}
 * @param tokenSecret      API token secret (UUID issued by PVE)
 * @param caCertPath       PEM file pinned as the only trusted CA for the PVE
 *                         API TLS endpoint; blank/null = JVM default trust store
 * @param connectTimeout   TCP connect timeout (default 5s)
 * @param readTimeout      per-request read timeout (default 30s)
 * @param taskPollInterval base interval between UPID task-status polls, jittered
 *                         ±20% (default 2s)
 * @param taskPollTimeout  default overall deadline for awaiting a task (default 5m)
 */
@ConfigurationProperties(prefix = "pickle.proxmox")
public record ProxmoxProperties(
        String tokenId,
        String tokenSecret,
        String caCertPath,
        Duration connectTimeout,
        Duration readTimeout,
        Duration taskPollInterval,
        Duration taskPollTimeout) {

    public ProxmoxProperties {
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(30);
        taskPollInterval = taskPollInterval != null ? taskPollInterval : Duration.ofSeconds(2);
        taskPollTimeout = taskPollTimeout != null ? taskPollTimeout : Duration.ofMinutes(5);
    }
}
