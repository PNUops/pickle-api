package kr.ac.pusan.pickle.publishing.dns;

import java.util.List;

/**
 * The dev/test provider: reports itself configured, accepts every write and
 * touches nothing, lists an empty zone. It exists so the state machine around
 * the records runs end to end where no zone is reachable, which is also why
 * it is never the default outside those profiles — a production api on this
 * provider would mark every name APPLIED while none of them resolves.
 */
public class NoopDnsRecordProvider implements DnsRecordProvider {

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void ensure(String fqdn, DnsRecordType type, List<String> values, int ttlSeconds) {
        // accepted, nothing written
    }

    @Override
    public void remove(String fqdn, DnsRecordType type) {
        // accepted, nothing written
    }

    @Override
    public List<DnsRecord> listRecords(String rootDomain) {
        return List.of();
    }
}
