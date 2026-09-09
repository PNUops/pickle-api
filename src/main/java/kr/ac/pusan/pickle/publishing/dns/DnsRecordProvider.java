package kr.ac.pusan.pickle.publishing.dns;

import java.util.List;

/**
 * The smallest surface the platform-subdomain lifecycle needs from a DNS
 * provider: put an A record up, take it down, and list the zone so a resync
 * can tell what exists. Writing records is a different concern from the
 * custom-domain verification lookups ({@code DnsResolver}), which read the
 * public resolver and never touch a zone.
 *
 * <p>Every write is idempotent by contract: {@link #ensure} on an existing
 * identical record set and {@link #remove} on an absent one both succeed. A
 * provider failure — transport, auth, a rejected change — surfaces as
 * {@link DnsProviderException}; callers record it and let the reconciler
 * retry. Names are plain FQDNs without the trailing dot.</p>
 *
 * <p>Writes name a {@link DnsRecordType} rather than a string, so the types
 * the platform is willing to put in its own zone are a list one can read
 * instead of whatever a caller passed. Reads are not restricted that way:
 * {@link #listRecords} reports the zone as it is, including types nothing here
 * writes, because the caller's own scope rule is what decides what may be
 * touched.</p>
 */
public interface DnsRecordProvider {

    /**
     * Whether writes can be attempted at all. False under the fail-closed
     * default and under a Google configuration missing its project, zone or
     * key file; the caller then refuses rather than pretends.
     */
    boolean configured();

    /**
     * Creates or replaces the record set at {@code fqdn} of {@code type} so it
     * holds exactly {@code values}, in that order.
     */
    void ensure(String fqdn, DnsRecordType type, List<String> values, int ttlSeconds);

    /** Removes the record set at {@code fqdn} of {@code type}; absent is a success. */
    void remove(String fqdn, DnsRecordType type);

    /** {@link #ensure} for the single-address A record a platform subdomain gets. */
    default void ensureA(String fqdn, String ipv4, int ttlSeconds) {
        ensure(fqdn, DnsRecordType.A, List.of(ipv4), ttlSeconds);
    }

    /** {@link #remove} for that same record. */
    default void removeA(String fqdn) {
        remove(fqdn, DnsRecordType.A);
    }

    /**
     * Every record set in the zone that holds {@code rootDomain} — all types,
     * not just A, so the caller's own scope rule is what decides what may be
     * touched and a test can prove it leaves the rest alone.
     */
    List<DnsRecord> listRecords(String rootDomain);
}
