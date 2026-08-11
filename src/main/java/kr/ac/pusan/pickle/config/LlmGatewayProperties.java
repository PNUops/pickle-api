package kr.ac.pusan.pickle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM gateway link settings ({@code pickle.llm-gateway.*}). The campus LLM
 * gateway daemon (LXC 103) polls {@code POST /internal/llm/sync} for its
 * authorization document and pushes usage/body batches to the sibling paths;
 * the surface has its own filter chain, its own static bearer and one rate
 * bucket per sub-path — nothing is shared with the sshgw filter, its global
 * bucket, or the relay chain.
 *
 * <p>The token has <b>no default</b> outside dev/test: when it is blank the
 * {@code /internal/llm/**} chain <b>fails closed</b> (every request rejected)
 * rather than accepting an empty bearer. {@code previousToken} exists from day
 * one so a rotation needs no coordinated restart: set the new secret as
 * {@code token}, move the old one to {@code previousToken}, restart the api,
 * then reconfigure the gateway at leisure — both values authenticate during
 * the overlap, and clearing {@code previousToken} ends it.</p>
 *
 * <p><b>One rate bucket per sub-path, never one for the link</b>: sync polls
 * every 5 seconds, bodies flush every 2 seconds, and usage fires a burst of
 * consecutive POSTs whenever a backlog drains. A single bucket sized for sync
 * would let a usage backlog 429 the authorization poll — the gateway would
 * then sit on its last document while the api believes it is current.</p>
 *
 * <p><b>Body caps are per sub-path too</b>: sync is a handful of gauges, a
 * usage batch is up to 500 events, and the gateway caps its own body batch at
 * 4 MiB before JSON transport overhead. A refused bodies batch is not retried,
 * so a cap below what the gateway sends silently discards captured text —
 * never lower these blindly.</p>
 *
 * @param token             static bearer secret from {@code PICKLE_LLM_GATEWAY_TOKEN}
 * @param previousToken     the outgoing secret during a rotation; blank when
 *                          no rotation is in flight
 * @param allowedSourceIp   the only TCP peer allowed on {@code /internal/llm/**}
 *                          (the LLM gateway LXC); defaults to 172.30.1.40
 * @param syncRateLimitPerMinute   sync-poll budget (default 60/min)
 * @param usageRateLimitPerMinute  usage-batch budget (default 120/min)
 * @param bodiesRateLimitPerMinute bodies-batch budget (default 120/min)
 * @param maxSyncBodyBytes   sync body cap (default 64 KiB)
 * @param maxUsageBodyBytes  usage body cap (default 4 MiB)
 * @param maxBodiesBodyBytes bodies body cap (default 8 MiB)
 */
@ConfigurationProperties(prefix = "pickle.llm-gateway")
public record LlmGatewayProperties(
        String token,
        String previousToken,
        String allowedSourceIp,
        Integer syncRateLimitPerMinute,
        Integer usageRateLimitPerMinute,
        Integer bodiesRateLimitPerMinute,
        Long maxSyncBodyBytes,
        Long maxUsageBodyBytes,
        Long maxBodiesBodyBytes) {

    public LlmGatewayProperties {
        allowedSourceIp = (allowedSourceIp != null && !allowedSourceIp.isBlank())
                ? allowedSourceIp : "172.30.1.40";
        syncRateLimitPerMinute = (syncRateLimitPerMinute != null && syncRateLimitPerMinute > 0)
                ? syncRateLimitPerMinute : 60;
        usageRateLimitPerMinute = (usageRateLimitPerMinute != null && usageRateLimitPerMinute > 0)
                ? usageRateLimitPerMinute : 120;
        bodiesRateLimitPerMinute = (bodiesRateLimitPerMinute != null && bodiesRateLimitPerMinute > 0)
                ? bodiesRateLimitPerMinute : 120;
        maxSyncBodyBytes = (maxSyncBodyBytes != null && maxSyncBodyBytes > 0)
                ? maxSyncBodyBytes : 65_536L;
        maxUsageBodyBytes = (maxUsageBodyBytes != null && maxUsageBodyBytes > 0)
                ? maxUsageBodyBytes : 4_194_304L;
        maxBodiesBodyBytes = (maxBodiesBodyBytes != null && maxBodiesBodyBytes > 0)
                ? maxBodiesBodyBytes : 8_388_608L;
    }

    /** True when no bearer secret is configured — the filter must fail closed. */
    public boolean tokenUnset() {
        return token == null || token.isBlank();
    }

    /** True when a rotation overlap is in flight (an old secret still valid). */
    public boolean previousTokenSet() {
        return previousToken != null && !previousToken.isBlank();
    }
}
