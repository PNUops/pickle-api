package kr.ac.pusan.pickle.publishing.dns;

import java.util.List;

/**
 * The smallest surface the platform-subdomain lifecycle needs from a DNS
 * provider: put an A record up, take it down, and list the zone so a resync
 * can tell what exists. Writing records is a different concern from the
 * custom-domain verification lookups ({@code DnsResolver}), which read the
 * public resolver and never touch a zone.
 *
 * <p>Every write is idempotent by contract: {@link #ensureA} on an existing
 * identical record and {@link #removeA} on an absent one both succeed. A
 * provider failure — transport, auth, a rejected change — surfaces as
 * {@link DnsProviderException}; callers record it and let the reconciler
 * retry. Names are plain FQDNs without the trailing dot.</p>
 */
public interface DnsRecordProvider {

    /**
     * Whether writes can be attempted at all. False under the fail-closed
     * default and under a Google configuration missing its project, zone or
     * key file; the caller then refuses rather than pretends.
     */
    boolean configured();

    /** Creates or replaces the A record for {@code fqdn} so it holds exactly {@code ipv4}. */
    void ensureA(String fqdn, String ipv4, int ttlSeconds);

    /** Removes the A record for {@code fqdn}; an absent record is a success. */
    void removeA(String fqdn);

    /**
     * Every record set in the zone that holds {@code rootDomain} — all types,
     * not just A, so the caller's own scope rule is what decides what may be
     * touched and a test can prove it leaves the rest alone.
     */
    List<DnsRecord> listRecords(String rootDomain);
}
