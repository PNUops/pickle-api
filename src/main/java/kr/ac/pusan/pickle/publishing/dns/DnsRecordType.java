package kr.ac.pusan.pickle.publishing.dns;

/**
 * The record types the platform is allowed to write.
 *
 * <p>Deliberately a short list rather than a free string. Reads see whatever
 * the zone holds ({@link DnsRecord#type()} stays a string for that reason),
 * but a write has to name one of these, so a type nobody has reasoned about
 * cannot reach the zone by way of a caller passing a literal. The two absent
 * on purpose are {@code NS}, which hands a name's whole subtree to somebody
 * else, and {@code CAA}, which decides who may issue certificates for it.</p>
 */
public enum DnsRecordType {
    A,
    AAAA,
    CNAME,
    TXT
}
