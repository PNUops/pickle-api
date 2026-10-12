package kr.ac.pusan.pickle.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Host-specific fixtures for a fresh development or test database. */
@ConfigurationProperties(prefix = "pickle.dev-inventory")
public record DevInventoryProperties(
        String rootDomain,
        String nodeName,
        String nodeApiHost,
        int nodeCpuThreads,
        int nodeMemoryMb,
        String nodeVmBridge,
        String nodeStorage,
        String ipPoolName,
        String ipPoolCidr,
        String ipPoolGateway,
        String ipPoolDns,
        String ipPoolReservedRanges,
        int templateVmid,
        int vmidStart,
        String relayName,
        String relaySourceIp,
        String relayPublicHost,
        int relayPortStart,
        int relayPortEnd,
        boolean enableGateways) {
}
