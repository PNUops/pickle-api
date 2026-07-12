package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal SSH-gateway route endpoint settings ({@code pickle.sshgw.*},
 * docs/api/internal.md Link 1). sshpiper on the sshgw LXC presents {@code token}
 * as a static bearer; the endpoint additionally pins the caller's source IP to
 * {@code allowedSourceIp} (defence in depth on top of the vmbr1 firewall) and
 * rate-limits per source IP.
 *
 * <p>The token has <b>no default</b> outside dev/test: when it is blank the
 * {@code /internal/**} filter chain <b>fails closed</b> (every request is
 * rejected) rather than accepting an empty bearer, so a prod profile that
 * forgot to set {@code PICKLE_SSHGW_TOKEN} hands out no routes.</p>
 *
 * @param token             static bearer secret from {@code PICKLE_SSHGW_TOKEN}
 * @param allowedSourceIp   the only TCP peer allowed to call {@code /internal/**}
 *                          (the sshgw LXC, 172.30.1.30); defaults to that
 * @param rateLimitPerMinute per-source-IP route-lookup budget (default 60/min)
 */
@ConfigurationProperties(prefix = "pickle.sshgw")
public record SshGatewayProperties(
        String token,
        String allowedSourceIp,
        Integer rateLimitPerMinute) {

    public SshGatewayProperties {
        allowedSourceIp = (allowedSourceIp != null && !allowedSourceIp.isBlank())
                ? allowedSourceIp : "172.30.1.30";
        rateLimitPerMinute = (rateLimitPerMinute != null && rateLimitPerMinute > 0)
                ? rateLimitPerMinute : 60;
    }

    /** True when no bearer secret is configured — the filter must fail closed. */
    public boolean tokenUnset() {
        return token == null || token.isBlank();
    }
}
