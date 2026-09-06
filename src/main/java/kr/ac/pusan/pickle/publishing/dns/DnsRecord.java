package kr.ac.pusan.pickle.publishing.dns;

import java.util.List;

/**
 * One record set as a provider reports it: the owner name without the
 * trailing dot, the type as the provider spells it ({@code A}, {@code NS},
 * ...) and the data values.
 */
public record DnsRecord(String name, String type, List<String> values, int ttlSeconds) {

    public DnsRecord {
        name = DnsNames.relative(name);
        values = values == null ? List.of() : List.copyOf(values);
    }
}
