package kr.ac.pusan.pickle.publishing.dns;

import java.util.List;

/**
 * The fail-closed provider: what runs when {@code pickle.dns.provider} is
 * {@code none} or a real provider lacks the settings it needs. It reports
 * unconfigured, so callers refuse up front; the write methods throw with the
 * same explanation in case one is reached anyway, so nothing ever records a
 * record that was never written.
 */
public class UnconfiguredDnsRecordProvider implements DnsRecordProvider {

    private final String reason;

    public UnconfiguredDnsRecordProvider(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public void ensureA(String fqdn, String ipv4, int ttlSeconds) {
        throw new DnsProviderException(reason);
    }

    @Override
    public void removeA(String fqdn) {
        throw new DnsProviderException(reason);
    }

    @Override
    public List<DnsRecord> listRecords(String rootDomain) {
        throw new DnsProviderException(reason);
    }
}
