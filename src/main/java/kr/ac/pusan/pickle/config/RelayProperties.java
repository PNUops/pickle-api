package kr.ac.pusan.pickle.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Relay sync-surface settings ({@code pickle.relay.*}). The relay agent polls
 * {@code POST /internal/relays/{id}/sync} over the tunnel; this surface has
 * its own filter chain, per-relay token and per-relay rate limit — nothing is
 * shared with the sshgw internal filter or its global bucket.
 *
 * @param syncRateLimitPerMinute per-relay sync budget (default 20/min — about
 *        3–5× the agent poll rate; deliberately its own scope so a runaway
 *        relay can never 429 the sshgw route path)
 * @param pollIntervalSeconds the agent's poll interval as configured on the
 *        relay (default 30 s); consumed by the contact-lost derivation
 *        (lost = no sync for 3× this)
 * @param firstContactGraceSeconds how long an ENABLED relay may exist without
 *        a single successful sync before the watchdog flags it (default
 *        900 s). Measured from the row's last admin-side write, so issuing a
 *        token restarts the window; generous enough for a manual agent
 *        install, small against the half-day outages this is meant to catch
 * @param maxSyncBodyBytes hard cap on a sync request body (default 1 MiB);
 *        a larger declared Content-Length is rejected 413 and chunked bodies
 *        are stream-capped
 * @param restrictedSourceIps peers confined to the relay sync surface: a
 *        relay's tunnel address must reach only {@code /internal/relays/**},
 *        every other path answers 403 (default: the seed relay's tunnel
 *        address)
 */
@ConfigurationProperties(prefix = "pickle.relay")
public record RelayProperties(
        Integer syncRateLimitPerMinute,
        Integer pollIntervalSeconds,
        Integer firstContactGraceSeconds,
        Long maxSyncBodyBytes,
        List<String> restrictedSourceIps) {

    public RelayProperties {
        syncRateLimitPerMinute = (syncRateLimitPerMinute != null && syncRateLimitPerMinute > 0)
                ? syncRateLimitPerMinute : 20;
        pollIntervalSeconds = (pollIntervalSeconds != null && pollIntervalSeconds > 0)
                ? pollIntervalSeconds : 30;
        firstContactGraceSeconds = (firstContactGraceSeconds != null && firstContactGraceSeconds > 0)
                ? firstContactGraceSeconds : 900;
        maxSyncBodyBytes = (maxSyncBodyBytes != null && maxSyncBodyBytes > 0)
                ? maxSyncBodyBytes : 1_048_576L;
        restrictedSourceIps = (restrictedSourceIps != null && !restrictedSourceIps.isEmpty())
                ? List.copyOf(restrictedSourceIps) : List.of("10.100.100.1");
    }
}
