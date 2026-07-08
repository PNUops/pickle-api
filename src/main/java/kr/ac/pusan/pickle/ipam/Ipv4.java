package kr.ac.pusan.pickle.ipam;

/** Minimal IPv4 arithmetic for candidate enumeration (no IPv6 in v1). */
final class Ipv4 {

    /** Inclusive address range; {@code first}/{@code last} exclude network/broadcast. */
    record Cidr(long network, long broadcast) {

        long firstUsable() {
            return network + 1;
        }

        long lastUsable() {
            return broadcast - 1;
        }
    }

    private Ipv4() {
    }

    /** Parses {@code a.b.c.d/prefix}; the host part, if any, is masked off. */
    static Cidr parseCidr(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("not CIDR notation: " + cidr);
        }
        long base = toLong(cidr.substring(0, slash));
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        if (prefix < 0 || prefix > 30) {
            // /31 and /32 have no usable host addresses for our purposes.
            throw new IllegalArgumentException("unsupported IPv4 prefix: " + cidr);
        }
        long size = 1L << (32 - prefix);
        long network = base & ~(size - 1);
        return new Cidr(network, network + size - 1);
    }

    /** Parses a dotted-quad host address; a {@code /mask} suffix is ignored. */
    static long toLong(String ip) {
        String host = ip;
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("not an IPv4 address: " + ip);
        }
        long value = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("not an IPv4 address: " + ip);
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    static String format(long ip) {
        return "%d.%d.%d.%d".formatted((ip >> 24) & 0xFF, (ip >> 16) & 0xFF, (ip >> 8) & 0xFF, ip & 0xFF);
    }
}
