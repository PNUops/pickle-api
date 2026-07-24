package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal SSH-gateway route endpoint settings ({@code pickle.sshgw.*},
 * internal route contract). sshpiper on the sshgw LXC presents {@code token}
 * as a static bearer; the endpoint additionally pins the caller's source IP to
 * {@code allowedSourceIp} (defence in depth on top of the vmbr1 firewall).
 *
 * <p>Rate limiting is two-tier: the transport peer is <b>always</b> the sshgw
 * LXC, so the meaningful per-client limit ({@code rateLimitPerMinute}) is keyed
 * on the PROXY-recovered client {@code sourceIp} inside the route service — a
 * peer-keyed limit would be one shared bucket that lets a single internet
 * abuser 429 every user's SSH login. The filter keeps only a high global
 * backstop ({@code globalRateLimitPerMinute}) bounding total abuse.</p>
 *
 * <p>The token has <b>no default</b> outside dev/test: when it is blank the
 * {@code /internal/**} filter chain <b>fails closed</b> (every request is
 * rejected) rather than accepting an empty bearer, so a prod profile that
 * forgot to set {@code PICKLE_SSHGW_TOKEN} hands out no routes.</p>
 *
 * @param token             static bearer secret from {@code PICKLE_SSHGW_TOKEN}
 * @param allowedSourceIp   the only TCP peer allowed to call {@code /internal/**}
 *                          (the sshgw LXC, 172.30.1.30); defaults to that
 * @param rateLimitPerMinute per-client (reported {@code sourceIp}) route-lookup
 *                          budget (default 60/min)
 * @param globalRateLimitPerMinute whole-gateway backstop applied at the filter,
 *                          across ALL clients (default 600/min)
 */
@ConfigurationProperties(prefix = "pickle.sshgw")
public record SshGatewayProperties(
        String token,
        String allowedSourceIp,
        Integer rateLimitPerMinute,
        Integer globalRateLimitPerMinute) {

    public SshGatewayProperties {
        allowedSourceIp = (allowedSourceIp != null && !allowedSourceIp.isBlank())
                ? allowedSourceIp : "172.30.1.30";
        rateLimitPerMinute = (rateLimitPerMinute != null && rateLimitPerMinute > 0)
                ? rateLimitPerMinute : 60;
        globalRateLimitPerMinute = (globalRateLimitPerMinute != null && globalRateLimitPerMinute > 0)
                ? globalRateLimitPerMinute : 600;
    }

    /** True when no bearer secret is configured — the filter must fail closed. */
    public boolean tokenUnset() {
        return token == null || token.isBlank();
    }
}
