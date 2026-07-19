package kr.ac.pusan.pickle.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M6.5 web-terminal settings ({@code pickle.terminal.*}, docs/api/internal.md
 * Link 3, docs/plan/05 Path B). pickle-api mints one-time tickets and answers the
 * bridge's control calls; it is the <b>client</b> only for the force-terminate
 * link (3b) to the bridge control port.
 *
 * <p>The inbound {@code /internal/terminal/*} calls reuse the existing
 * {@code /internal} filter chain (source 172.30.1.30 + {@code PICKLE_SSHGW_TOKEN}),
 * so no separate inbound token lives here. The outbound control token
 * ({@code bridgeControlToken}) has <b>no default</b> outside dev/test: when blank
 * the terminate call fails closed with 503 rather than sending an empty bearer.</p>
 *
 * @param bridgeControlBaseUrl  bridge control listener, e.g. {@code http://172.30.1.30:8083}
 * @param bridgeControlToken    static bearer for control ({@code PICKLE_TERMINAL_CONTROL_TOKEN})
 * @param bridgeConnectTimeout  TCP connect timeout for the control call (default 2s)
 * @param bridgeReadTimeout     per-request read timeout for the control call (default 3s)
 * @param ticketTtl             one-time ticket lifetime from mint (default 60s)
 * @param perUserCap            max concurrent live sessions per user (default 3)
 * @param perVmCap              max concurrent live sessions per VM (default 5)
 * @param perOrgCap             max concurrent live sessions per owning org (default 20)
 * @param rateLimitPerMinute    ticket-mint budget, applied on BOTH client IP and userId
 * @param pendingGrace          a redeemed-but-never-started mirror entry is pruned after
 *                              this idle age (default 120s ≈ ticket TTL + slack); covers a
 *                              bridge that dies between redeem and session-start
 * @param staleAfter            a started mirror entry with no revalidation heartbeat for
 *                              this long is pruned (default 330s ≈ 60s poll × 5 + slack);
 *                              covers a hard-killed bridge that never reports session-end
 * @param enforceSingleInstance boot-time PG-advisory single-instance assertion (default true)
 */
@ConfigurationProperties(prefix = "pickle.terminal")
public record TerminalProperties(
        String bridgeControlBaseUrl,
        String bridgeControlToken,
        Duration bridgeConnectTimeout,
        Duration bridgeReadTimeout,
        Duration ticketTtl,
        Integer perUserCap,
        Integer perVmCap,
        Integer perOrgCap,
        Integer rateLimitPerMinute,
        Duration pendingGrace,
        Duration staleAfter,
        Boolean enforceSingleInstance) {

    public TerminalProperties {
        bridgeControlBaseUrl = notBlank(bridgeControlBaseUrl, "http://172.30.1.30:8083");
        bridgeConnectTimeout = bridgeConnectTimeout != null ? bridgeConnectTimeout : Duration.ofSeconds(2);
        bridgeReadTimeout = bridgeReadTimeout != null ? bridgeReadTimeout : Duration.ofSeconds(3);
        ticketTtl = ticketTtl != null && !ticketTtl.isZero() && !ticketTtl.isNegative()
                ? ticketTtl : Duration.ofSeconds(60);
        perUserCap = positiveOr(perUserCap, 3);
        perVmCap = positiveOr(perVmCap, 5);
        perOrgCap = positiveOr(perOrgCap, 20);
        rateLimitPerMinute = positiveOr(rateLimitPerMinute, 10);
        pendingGrace = positiveDuration(pendingGrace, Duration.ofSeconds(120));
        staleAfter = positiveDuration(staleAfter, Duration.ofSeconds(330));
        enforceSingleInstance = enforceSingleInstance == null || enforceSingleInstance;
    }

    /** True when no control bearer is configured — the terminate call fails closed. */
    public boolean controlTokenUnset() {
        return bridgeControlToken == null || bridgeControlToken.isBlank();
    }

    private static String notBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static int positiveOr(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative() ? value : fallback;
    }
}
