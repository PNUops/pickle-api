package kr.ac.pusan.pickle.publishing;

import java.util.List;

/**
 * Minimal DNS lookup used by custom-domain verification (docs/plan/06). An
 * interface so tests can supply deterministic records instead of hitting the
 * live resolver.
 */
public interface DnsResolver {

    /** TXT record values for {@code name} (quotes stripped); empty if none/unresolvable. */
    List<String> txtRecords(String name);

    /** A record IPv4 addresses for {@code name}; empty if none/unresolvable. */
    List<String> aRecords(String name);
}
